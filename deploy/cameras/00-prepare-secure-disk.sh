#!/bin/bash
# Stage 0 — encrypted storage for camera recordings, face images and camera settings.
#
# Erases the internal eMMC (/dev/mmcblk0, the old Windows), creates one LUKS2
# partition, binds it to the TPM (auto-unlock at boot, PCR 7) and installs a
# systemd unit that unlocks and mounts it before Docker / Home Assistant start:
#
#   /dev/mmcblk0p1 --LUKS2--> /dev/mapper/secure --> /srv/secure
#       /srv/secure/frigate  --bind--> <HA data>/media/frigate  (Frigate add-on /media/frigate;
#                                       <HA data> is read from /etc/hassio.json, here /var/lib/homeassistant)
#       /srv/secure/alarm                                                (server.js camera settings)
#
# Both mountpoints are made immutable (chattr +i) while unmounted, so if unlock
# ever fails nothing is written to the USB stick by mistake — Frigate just fails
# to record, while Home Assistant and the alarm server keep running.
#
# Run as root on the Mini PC:   bash 00-prepare-secure-disk.sh
set -euo pipefail

DEV=/dev/mmcblk0
PART=${DEV}p1
MAPPER=secure
MNT=/srv/secure
# HA Supervised data dir comes from /etc/hassio.json ("data"); default /usr/share/hassio.
HASSIO_DATA=$(sed -n 's/.*"data"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' /etc/hassio.json 2>/dev/null)
HASSIO_DATA=${HASSIO_DATA:-/usr/share/hassio}
HASSIO_MEDIA=$HASSIO_DATA/media
FRIGATE_DIR=$HASSIO_MEDIA/frigate
HELPER=/usr/local/sbin/secure-storage.sh
CONF=/etc/secure-storage.conf
UNIT=/etc/systemd/system/secure-storage.service

die() { echo "ERROR: $*" >&2; exit 1; }

# ── Safety checks ────────────────────────────────────────────────────────────
[[ $EUID -eq 0 ]]         || die "run as root (su -)"
[[ -b $DEV ]]             || die "$DEV not found"
[[ -d $HASSIO_MEDIA ]]    || die "$HASSIO_MEDIA not found - is this the HA Supervised host?"
[[ -e /dev/tpmrm0 ]]      || die "TPM (/dev/tpmrm0) not found"
[[ ! -e $UNIT ]]          || die "already installed ($UNIT exists)"
[[ ! -e $FRIGATE_DIR ]]   || die "$FRIGATE_DIR already exists - aborting to avoid hiding its data"

rootsrc=$(findmnt -no SOURCE /)
[[ $rootsrc != ${DEV}* ]] || die "root filesystem is on $DEV - aborting"
if lsblk -no MOUNTPOINT "$DEV" | grep -q .; then
  die "a partition of $DEV is mounted - aborting"
fi

echo
lsblk -o NAME,SIZE,TYPE,FSTYPE,LABEL "$DEV"
echo
echo "!!! ALL DATA ON $DEV (internal eMMC, old Windows) WILL BE ERASED !!!"
echo "    The system disk ($rootsrc) is NOT touched."
read -r -p "Type ERASE to continue: " ans
[[ $ans == ERASE ]] || die "cancelled"

# ── Packages ─────────────────────────────────────────────────────────────────
apt-get update
apt-get install -y cryptsetup clevis clevis-luks clevis-tpm2 parted e2fsprogs

# ── Partition + LUKS ─────────────────────────────────────────────────────────
for p in "${DEV}"p*; do [[ -b $p ]] && wipefs -a "$p" || true; done
wipefs -a "$DEV"
parted -s "$DEV" mklabel gpt mkpart secure 1MiB 100%
partprobe "$DEV" || true
udevadm settle
[[ -b $PART ]] || die "$PART was not created"

echo
echo "=== Choose a RECOVERY passphrase (long, e.g. 5-6 random words). ==="
echo "=== Write it on paper and keep it away from this computer.       ==="
echo "=== It is needed only if the TPM auto-unlock ever stops working. ==="
cryptsetup luksFormat --type luks2 --batch-mode --verify-passphrase "$PART"

echo
echo "=== Enter the recovery passphrase to open the disk ==="
cryptsetup open "$PART" "$MAPPER"
mkfs.ext4 -q -L secure "/dev/mapper/$MAPPER"

# ── Directory layout ─────────────────────────────────────────────────────────
mkdir -p "$MNT" "$FRIGATE_DIR"
chmod 700 "$MNT"
mount "/dev/mapper/$MAPPER" "$MNT"
mkdir -p "$MNT/frigate" "$MNT/alarm"
chmod 750 "$MNT/frigate"
chmod 700 "$MNT/alarm"
umount "$MNT"
cryptsetup close "$MAPPER"
# Immutable while unmounted: writes fail instead of landing on the USB stick.
chattr +i "$MNT" "$FRIGATE_DIR"

# ── TPM binding ──────────────────────────────────────────────────────────────
echo
echo "=== Binding to the TPM - enter the recovery passphrase once more ==="
clevis luks bind -d "$PART" tpm2 '{"pcr_bank":"sha256","pcr_ids":"7"}'

UUID=$(blkid -s UUID -o value "$PART")
[[ -n $UUID ]] || die "could not read UUID of $PART"

# ── Boot-time unlock + mount ─────────────────────────────────────────────────
cat > "$CONF" <<EOF
UUID=$UUID
MAPPER=$MAPPER
MNT=$MNT
FRIGATE_DIR=$FRIGATE_DIR
EOF
chmod 600 "$CONF"

cat > "$HELPER" <<'EOF'
#!/bin/bash
# Unlock (TPM via clevis) and mount the encrypted camera storage.
# Installed by deploy/cameras/00-prepare-secure-disk.sh
set -euo pipefail
source /etc/secure-storage.conf
DEV=/dev/disk/by-uuid/$UUID

case "${1:-}" in
  start)
    for _ in $(seq 30); do [[ -e /dev/tpmrm0 && -e $DEV ]] && break; sleep 1; done
    [[ -e /dev/mapper/$MAPPER ]] || clevis luks unlock -d "$DEV" -n "$MAPPER"
    mountpoint -q "$MNT"         || mount -o noatime,nodev,nosuid,noexec "/dev/mapper/$MAPPER" "$MNT"
    mountpoint -q "$FRIGATE_DIR" || mount --bind "$MNT/frigate" "$FRIGATE_DIR"
    ;;
  stop)
    if mountpoint -q "$FRIGATE_DIR"; then umount "$FRIGATE_DIR" || true; fi
    if mountpoint -q "$MNT";         then umount "$MNT"         || true; fi
    if [[ -e /dev/mapper/$MAPPER ]]; then cryptsetup close "$MAPPER" || true; fi
    ;;
  *)
    echo "usage: $0 start|stop" >&2; exit 2 ;;
esac
EOF
chmod 700 "$HELPER"

cat > "$UNIT" <<'EOF'
[Unit]
Description=Encrypted camera storage (TPM unlock + mount)
After=local-fs.target
Before=docker.service hassio-supervisor.service alarm-server.service

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/usr/local/sbin/secure-storage.sh start
ExecStop=/usr/local/sbin/secure-storage.sh stop
TimeoutStartSec=90

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable secure-storage.service

# ── Test: unlock through the TPM only (no passphrase) ────────────────────────
echo
echo "=== Testing TPM unlock (should NOT ask for a passphrase) ==="
systemctl start secure-storage.service
findmnt "$MNT"
findmnt "$FRIGATE_DIR"
df -h "$MNT"

echo
echo "OK - stage 0 done. Now reboot and run the checks from README.md:"
echo "  systemctl status secure-storage --no-pager"
echo "  findmnt $FRIGATE_DIR"
