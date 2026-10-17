# Daroyar promo video — progress (resume from here)

Status (2026-10-17): composition DONE and `hyperframes check` PASSES (0 errors, 23/23 contrast). Remaining: render MP4, verify, send to Alireza.

## Resume steps

```bash
cd promo                      # this folder (HyperFrames project root)
export HYPERFRAMES_BROWSER_PATH=/opt/pw-browsers/chromium_headless_shell-1194/chrome-linux/headless_shell
npx --yes hyperframes@0.8.167 check                         # should pass
npx --yes hyperframes@0.8.167 snapshot --at 1.9,8.6,17.5,22.2,29.2,38.5,47 --no-end -o snaps
npx --yes hyperframes@0.8.167 render -o daroyar-demo.mp4     # 1080x1920, 48.5s
ffprobe daroyar-demo.mp4                                    # check duration + audio stream
```

(The cloud sandbox cannot download chrome-headless-shell, so point HYPERFRAMES_BROWSER_PATH at any local Chromium.)

## What's in it

- `index.html` — single composition, 48.5s, 9 scenes + 9 Persian subtitles + 37 SFX `<audio>` clips (no music).
- Scenes: hook (clock, «مامان قرصشو خورد؟ بابا چی؟») → app icon «دارویار» → child adds medicine in Telegram Mini App (real screenshot + typing) → MOM'S PHONE RINGS (lock screen ۷:۵۹→۸:۰۰, screen wakes, vibrates, alarm beeps, «وقت داروست», buttons «خوردم»/«نخوردم» pop in, tap خوردم → «ثبت شد») → Telegram family group alerts (✅ مامان خورد, ⚠️ بابا بی‌جواب) → caregiver Android app (real screenshot) → «گوشی پدر و مادر: فقط اندروید» / «گوشی شما: هر گوشی‌ای» → «همین شهر باشی یا اون سر دنیا… حواست به مامان و بابا هست.» → end card: logo, علیرضا تهرانی / Alireza Tehrani, پیج اینستاگرام @alirezaai.ca.
- `assets/img/*-p.png` — real screenshots relabeled (feshar/1:23/maman → قرص فشار/۸:۰۰/مامان); originals kept next to them.
- `assets/sfx/alarm-ring.mp3` — synthesized alarm beeps + vibration buzz (6.84s); the rest are HyperFrames' bundled SFX.
- `assets/fonts/` — Vazirmatn FD (Persian digits), OFL.
- `BRIEF.md`, `STORYBOARD.md` — brief and scene plan.

## Gotchas learned

- Never put `dir="rtl"` on `<html>` — renders a black video. RTL is scoped to `.in` / `.cap`.
- Phone screens are 1080x2400 "viewport" divs scaled by 516/1080; overlay coordinates are in screenshot pixels.
- Camera zoom = `cam(sel, t, dur, scale, screenX, screenY, frameX, frameY)` in the script.
