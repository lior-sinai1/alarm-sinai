איך אני# הגדרת Firebase — שלב אחר שלב

---

## שלב 1 — צור פרויקט Firebase

1. כנס ל https://console.firebase.google.com
2. לחץ **"Add project"**
3. שם הפרויקט: `alarm-sinai`
4. **השבת** Google Analytics (לא נדרש)
5. לחץ **"Create project"**

---

## שלב 2 — הוסף אפליקציה Android

1. בדשבורד לחץ על אייקון **Android** (</> Android)
2. **Android package name:** `com.alarmsinai`
3. **App nickname:** `Alarm Sinai`
4. לחץ **"Register app"**
5. לחץ **"Download google-services.json"**
6. שמור את הקובץ בנתיב:
   ```
   alarm-sinai/android/app/google-services.json
   ```
   (החלף את הקובץ הקיים שהוא placeholder)

---

## שלב 3 — Service Account לשרת Node.js

1. בדשבורד Firebase לחץ על גלגל השיניים → **Project settings**
2. כנס ללשונית **"Service accounts"**
3. לחץ **"Generate new private key"**
4. אשר → הורד את קובץ ה-JSON
5. שמור אותו בשרת הלינוקס:
   ```bash
   # בשרת Debian:
   cp ~/firebase-service-account.json /opt/alarm-server/firebase-service-account.json
   chmod 600 /opt/alarm-server/firebase-service-account.json
   ```

---

## שלב 4 — התקן והרץ את השרת

כל הבקשות לשרת (חוץ מ-`/health`) דורשות מפתח API בכותרת `X-API-Key`. צור מפתח והגדר אותו כמשתנה סביבה:

```bash
openssl rand -hex 32          # מפתח של 64 תווים — שמור אותו
```

```bash
cd /opt/alarm-server
npm install
ALARM_API_KEY=<המפתח> node server.js
```

אם רצים כשירות, שים את השורה `ALARM_API_KEY=<המפתח>` בקובץ נפרד (`chmod 600`) והפנה אליו עם `EnvironmentFile=` ב-systemd.
אם המשתנה חסר או קצר מ-32 תווים, ה-API ננעל (תשובה 503) וה-polling וההתראות ממשיכים לעבוד.

פלט תקין:
```
Firebase initialized
Alarm server listening on port 3000
Modbus connected to 192.168.1.50
```

---

## שלב 5 — בנה את ה-APK

**לפני הבנייה** הוסף את אותו מפתח ל-`android/local.properties` (הקובץ ב-`.gitignore`, לא נכנס ל-git):
```
alarm.apiKey=<אותו מפתח כמו ב-ALARM_API_KEY בשרת>
```
המפתח נכנס ל-APK ולאפליקציית השעון בזמן הבנייה. ה-APK שנבנה ב-GitHub Actions נבנה **בלי** מפתח, בכוונה: הריפו ציבורי, וכל משתמש GitHub מחובר יכול להוריד את ה-artifact ולחלץ ממנו את המפתח. לכן בנה את ה-APK המשמש בפועל מקומית.

1. פתח **Android Studio**
2. **File → Open** → בחר תיקיית `alarm-sinai/android/`
3. המתן ל-Gradle sync להסתיים
4. **Build → Generate Signed Bundle / APK → APK**
5. בחר **debug** (לבדיקה) או צור keystore ל-release
6. ה-APK נמצא ב: `android/app/build/outputs/apk/debug/app-debug.apk`

---

## שלב 6 — התקן על הטלפון

**אפשרות א — USB:**
```bash
adb install app-debug.apk
```

**אפשרות ב — ישירות:**
- העתק את ה-APK לטלפון
- אפשר "מקורות לא ידועים" ב-Settings → Security
- פתח את הקובץ והתקן

---

## שלב 7 — גישה מרחוק: ngrok + Nginx (דומיין אחד לשתי האפליקציות)

דומיין ה-ngrok הוא יחיד, אז Nginx מקומי מחלק את התעבורה לפי נתיב:

```
ngrok (demystify-unplug-sassy.ngrok-free.dev)
  └─► nginx 127.0.0.1:8080
        ├─ /alarm/*   ─► שרת האזעקה  127.0.0.1:3000  (הקידומת /alarm נחתכת)
        └─ כל השאר    ─► Home Assistant 127.0.0.1:8123
```

**1. התקן והפעל את Nginx:**
```bash
sudo apt install nginx
sudo cp deploy/nginx/alarm-sinai.conf /etc/nginx/conf.d/alarm-sinai.conf
sudo nginx -t && sudo systemctl reload nginx
```

**2. Home Assistant** — ב-`configuration.yaml` (Nginx מתחבר מ-loopback):
```yaml
http:
  use_x_forwarded_for: true
  trusted_proxies:
    - 127.0.0.1
    - ::1
```
אם HA רץ ב-Docker בלי `network_mode: host`, הבקשות מגיעות מכתובת ה-gateway של Docker (למשל `172.17.0.1`) ולא מ-`127.0.0.1`. הוסף אותה ל-`trusted_proxies`.

**3. ngrok** — הפנה את הדומיין ל-Nginx (לא ל-8123 ולא ל-3000):
```bash
ngrok http --url=demystify-unplug-sassy.ngrok-free.dev 8080
```

**4. בדיקה:**
```bash
curl http://127.0.0.1:8080/alarm/health                              # {"ok":true,...}
curl -I http://127.0.0.1:8080/                                       # Home Assistant
curl https://demystify-unplug-sassy.ngrok-free.dev/alarm/health      # דרך ngrok (פתוח, בלי מפתח)

# דורשים מפתח:
curl -i https://demystify-unplug-sassy.ngrok-free.dev/alarm/status                          # 401
curl -i -H "X-API-Key: $ALARM_API_KEY" https://demystify-unplug-sassy.ngrok-free.dev/alarm/status   # 200
```

**5. האפליקציה** — `DEFAULT_URL` ב-`AlarmRepository.kt` הוא כעת
`https://demystify-unplug-sassy.ngrok-free.dev/alarm`. אין מסך הגדרות לשינוי הכתובת, לכן צריך לבנות ולהתקין APK חדש (שלב 5).
APK ישן פונה לשורש הדומיין (שם יושב Home Assistant) ולא שולח מפתח, ולכן יפסיק לעבוד. יש להחליף אותו בכל הטלפונים.

אם משהו נוסף קורא לשרת האזעקה (למשל `rest_command` או חיישן REST ב-Home Assistant), הוא חייב לשלוח את הכותרת `X-API-Key` ולפנות ל-`/alarm/...`.

---

## בדיקה מהירה

```bash
# בדוק שהשרת עובד:
curl http://192.168.1.92:3000/health
# תגובה: {"ok":true,"connected":true}

curl -H "X-API-Key: $ALARM_API_KEY" http://192.168.1.92:3000/status
# תגובה: {"ok":true,"connected":true,"m175":0,"m19":0,...}
# בלי הכותרת: 401 (או 503 אם ALARM_API_KEY לא הוגדר בשרת)
```
