# הגדרת Firebase — שלב אחר שלב

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

```bash
cd /opt/alarm-server
npm install
node server.js
```

פלט תקין:
```
Firebase initialized
Alarm server listening on port 3000
Modbus connected to 192.168.1.50
```

---

## שלב 5 — בנה את ה-APK

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

## שלב 7 — הגדר Cloudflare Tunnel (גישה מרחוק)

```bash
# בשרת Debian:
curl -L https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb -o cloudflared.deb
sudo dpkg -i cloudflared.deb

cloudflared tunnel login
cloudflared tunnel create alarm-sinai
cloudflared tunnel route dns alarm-sinai alarm.YOURDOMAIN.com
cloudflared tunnel run alarm-sinai
```

לאחר מכן עדכן ב-`AlarmRepository.kt`:
```kotlin
const val DEFAULT_URL = "https://alarm.YOURDOMAIN.com"
```

---

## בדיקה מהירה

```bash
# בדוק שהשרת עובד:
curl http://192.168.1.92:3000/health
# תגובה: {"ok":true,"connected":true}

curl http://192.168.1.92:3000/status
# תגובה: {"ok":true,"connected":true,"m175":0,"m19":0,...}
```
