#!/bin/bash
# Stage 1 — first Frigate config (one camera) for the HA "Frigate" add-on (0.17+ schema).
#
# Writes <HA data>/app_configs/<slug>_frigate/config.yml (older Supervisor: addon_configs). Asks for the camera
# user/password (hidden input) so they never appear on screen or in chat.
# The file is root-only (600). Later the alarm server replaces this config
# through the Frigate API with the cameras configured in the app.
#
# Run as root on the Mini PC:   bash 01-frigate-config.sh
set -euo pipefail

CAM_IP=${CAM_IP:-192.168.1.10}
CAM_ID=cam_1

die() { echo "ERROR: $*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "run as root (su -)"
HASSIO_DATA=$(sed -n 's/.*"data"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' /etc/hassio.json 2>/dev/null)
HASSIO_DATA=${HASSIO_DATA:-/usr/share/hassio}

mountpoint -q "$HASSIO_DATA/media/frigate" || die "encrypted disk not mounted on $HASSIO_DATA/media/frigate (systemctl status secure-storage)"

shopt -s nullglob
dirs=("$HASSIO_DATA"/app_configs/*_frigate "$HASSIO_DATA"/addon_configs/*_frigate)
shopt -u nullglob
if [[ ${#dirs[@]} -ne 1 ]]; then
  echo "Frigate add-on config folders found:"; ls -1 "$HASSIO_DATA"/app_configs/ "$HASSIO_DATA"/addon_configs/ 2>/dev/null || true
  die "expected exactly one *_frigate folder (found ${#dirs[@]})"
fi
CFG_DIR=${dirs[0]}
CFG=$CFG_DIR/config.yml
[[ ! -e $CFG ]] || die "$CFG already exists - not overwriting"

echo "Camera: $CAM_IP   config: $CFG"
read -r -p "Camera username (usually admin): " CAM_USER
read -r -s -p "Camera password (hidden): " CAM_PASS; echo
[[ -n $CAM_USER ]] || die "empty username"
# XMEye puts the credentials inside the URL path; these characters would break it.
[[ $CAM_USER$CAM_PASS != *[\&\?\#\ \"\'\\]* ]] || die "username/password contain & ? # space quote or backslash - change the camera password first"

main="rtsp://$CAM_IP:554/user=$CAM_USER&password=$CAM_PASS&channel=1&stream=0.sdp?"
sub="rtsp://$CAM_IP:554/user=$CAM_USER&password=$CAM_PASS&channel=1&stream=1.sdp?"

umask 077
cat > "$CFG" <<EOF
# Written by deploy/cameras/01-frigate-config.sh - later managed by the alarm server.
mqtt:
  enabled: false

database:
  path: /media/frigate/frigate.db      # encrypted disk

detectors:
  ov:
    type: openvino
    device: GPU

model:
  width: 300
  height: 300
  input_tensor: nhwc
  input_pixel_format: bgr
  path: /openvino-model/ssdlite_mobilenet_v2.xml
  labelmap_path: /openvino-model/coco_91cl_bkgr.txt

ffmpeg:
  hwaccel_args: preset-vaapi

go2rtc:
  streams:
    $CAM_ID:
      - "$main"
    ${CAM_ID}_sub:
      - "$sub"

cameras:
  $CAM_ID:
    ffmpeg:
      inputs:
        - path: rtsp://127.0.0.1:8554/$CAM_ID
          input_args: preset-rtsp-restream
          roles: [record]
        - path: rtsp://127.0.0.1:8554/${CAM_ID}_sub
          input_args: preset-rtsp-restream
          roles: [detect]
    detect:
      enabled: true
      fps: 5
    objects:
      track: [person, car, dog, cat]
    record:
      enabled: true
      continuous:
        days: 0
      motion:
        days: 0
      alerts:
        retain:
          days: 7
          mode: active_objects
      detections:
        retain:
          days: 7
          mode: active_objects
    snapshots:
      enabled: true
      retain:
        default: 7
EOF
chmod 600 "$CFG"
unset CAM_PASS main sub

echo
echo "OK - written $CFG (root only). Password is NOT shown here:"
sed -E 's/password=[^&]*/password=*****/' "$CFG" | sed -n '/^go2rtc/,/^cameras/p'
echo
echo "Next: in Home Assistant -> Apps -> Frigate -> Start, then open the Log tab."
