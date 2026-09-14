-- Fibaro Virtual Device — Main Loop
--
-- קוד זה נכנס תחת "Main Loop" של ה-Virtual Device (לא Scene, ולא כפתור).
-- הוא רץ אוטומטית כל X מילישניות (מוגדר בהגדרות ה-Main Loop של ה-VD, מומלץ 2000).
--
-- ה-VD לא מתחבר לחיישן ישירות — הוא רק קורא את המשתנים הגלובליים
-- (TuyaMotion / TuyaLux / TuyaTemp / TuyaHumidity) שה-hub (הגשר החיצוני) כבר
-- מעדכן, ומציג אותם כמכשיר אמיתי בפאנל של Fibaro. לכן אין צורך בשום שינוי
-- בגשר עצמו כדי שזה יעבוד — ברגע שה-hub רץ והמשתנים מתעדכנים, ה-VD יציג אותם.

local id = fibaro:getSelfId()

-- --- תנועה ---
local motion = fibaro:getGlobalValue("TuyaMotion") == "1"

fibaro:call(id, "setProperty", "value", motion and "1" or "0")
fibaro:call(id, "setProperty", "ui.lblStatus.value",
  motion and "🔴 זוהתה תנועה" or "⚪ אין תנועה")

-- --- אור / חום / לחות (רק אם הגדרת אותם ב-config.json של הגשר) ---
local lux  = fibaro:getGlobalValue("TuyaLux")
local temp = fibaro:getGlobalValue("TuyaTemp")
local hum  = fibaro:getGlobalValue("TuyaHumidity")

if lux ~= "" and lux ~= nil then
  fibaro:call(id, "setProperty", "ui.lblEnv.value",
    string.format("💡 %s lux    🌡 %s°C    💧 %s%%", lux, temp, hum))
end

-- --- חותמת זמן לעדכון האחרון (שימושי לוודא שהגשר עדיין חי) ---
fibaro:call(id, "setProperty", "ui.lblUpdated.value",
  "עודכן: " .. os.date("%H:%M:%S"))
