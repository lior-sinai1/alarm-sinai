--[[
%% properties
%% globals TuyaMotion
--]]

-- Scene: מגיב לעדכון משתנה גלובלי TuyaMotion שמגיע מ-hub (הגשר בין חיישן ה-Tuya ל-Fibaro).
-- הבלוק "%% globals TuyaMotion" למעלה גורם ל-Scene הזה לרוץ אוטומטית בכל פעם
-- שהגשר מעדכן את הערך (0/1). אפשר גם להגדיר את הטריגר דרך ה-GUI של ה-Scene
-- (Trigger scene when: global variable changes) במקום/בנוסף לבלוק הזה.

local motionActive = fibaro:getGlobalValue("TuyaMotion") == "1"

-- אם הגדרת גם חיישני אור/חום/לחות ב-config.json של הגשר, אפשר לקרוא אותם כאן:
-- local lux         = tonumber(fibaro:getGlobalValue("TuyaLux")) or 0
-- local temperature = tonumber(fibaro:getGlobalValue("TuyaTemp")) or 0
-- local humidity    = tonumber(fibaro:getGlobalValue("TuyaHumidity")) or 0

if motionActive then
  fibaro:debug("Tuya motion: DETECTED")

  -- --- בחר/הפעל את הפעולה הרצויה (מחק את ה-- כדי להפעיל) ---

  -- 1) התראת Push לכל המשתמשים:
  -- fibaro:call(1, "sendPush", "זוהתה תנועה בחיישן Tuya")

  -- 2) הדלקת מכשיר (למשל תאורה) — החלף 5 ב-ID האמיתי של המכשיר:
  -- fibaro:call(5, "turnOn")

  -- 3) הדלקת תאורה רק אם גם חשוך (משתמש בערך ה-lux מלמעלה):
  -- if lux < 10 then
  --   fibaro:call(5, "turnOn")
  -- end

  -- 4) הפעלת "Home Security" של Fibaro כפריצה (HC3; ID של החיישן הווירטואלי
  --    שהגדרת במודול ה-Security):
  -- fibaro:call(VIRTUAL_MOTION_DEVICE_ID, "pressButton", "1")

else
  fibaro:debug("Tuya motion: cleared")
  -- לדוגמה: כיבוי תאורה שהודלקה קודם
  -- fibaro:call(5, "turnOff")
end
