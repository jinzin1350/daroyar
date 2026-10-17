---
workflow: general-video
flow: automation
storyboard: no
message: "دارویار: بچه‌ها از هر جا داروهای مامان و بابا را تنظیم می‌کنند، گوشی آن‌ها سر ساعت زنگ می‌خورد و فقط «خوردم/نخوردم» می‌زنند، و بچه‌ها در تلگرام یا اپ خبردار می‌شوند."
destination: instagram-reels
aspect: 1080x1920
language: fa
length: 45-50s
---

## Intent

Persian demo/promo reel for Alireza Tehrani's page (@alirezaai.ca) showing the Daroyar app he built for his parents.
Audience: any family that wants to keep an eye on their parents' medicines; the children can be anywhere — even in the same city. It is about caring, not about distance.

User went to sleep and said "build whatever is good" — storyboard review skipped (flow: automation), render approved up front.

## Must say / show

- Children enter the medicines and times; parents type nothing.
- At dose time the PARENT'S PHONE RINGS (shown ringing, vibrating, screen lighting up) and the two buttons appear: «✅ خوردم» / «❌ نخوردم». (explicit user request)
- Children check through Telegram AND through the app.
- The parents' side must be Android only.
- End card: his logo (blue A), his name, his page address @alirezaai.ca.

## Assets

- assets/img/app-carehome.jpg, app-device.jpg — real Android caregiver screens (user screenshots)
- assets/img/tg-meds.jpg, tg-newmed.jpg — real Telegram Mini App screens (user screenshots)
- assets/img/logo.jpg — his logo; assets/img/insta.jpg — his profile (reference for brand style: navy + electric blue + yellow headline accents)
- Parent alarm screen and Telegram group chat are recreated in HTML from the app/bot source (AlarmActivity.kt colors and texts, bot alert wording).

## Customizations

- No music. Motion sound effects only (whoosh, pop, click, typing, notification, chime, synthesized alarm beeps + vibration buzz).
- Persian subtitles throughout (caption band in the Reels safe zone).
- Font: Vazirmatn (Farsi digits), bundled locally.

## Notes

- Test data in the real screenshots ("feshar — 2", 1:23, "maman") is relabeled to "قرص فشار — یک عدد", ۸:۰۰, "مامان" so every screen tells one consistent story.
- No Telegram/Android logos or mascots drawn; generic icons + words instead.
