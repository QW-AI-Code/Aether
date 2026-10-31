# Aether 1.6.0 — automatic GitHub TEST signing

## What changed

The SAME public CI test key from the previously working source packages is restored
at `.github/ci-keystore.jks.b64`. It is byte-identical, not regenerated. Its actual
certificate SHA-256 matches the retained `.github/expected-signer-ci.txt` pin:

`91b3019f60bff82594c342e5123c5cfaf8d14d9bdefb3be5cfd8187459cd7583`

No app/runtime/native source changes were made for this signing repair. The app
remains **1.6.0**, base versionCode **19**, engine **2.3.0**.

## GitHub usage — no manual signing Secrets required for the TEST path

1. Extract the ZIP and upload the CONTENTS of `Aether-1.6.0` to the repository root,
   including `.github` and its bundled CI test-key file. Do not nest the whole
   `Aether-1.6.0` folder beneath the repository root.
2. Push to main/master or run the existing Build APK workflow from Actions.
3. Without optional private signing Secrets, the workflow automatically decodes
   the bundled TEST key to RUNNER_TEMP and sets Gradle's signing environment.
   Do not manually enter the four signing Secrets for this normal TEST build.

The workflow already supported this behavior; the previous key-free package was
missing the key. The actual fix is restoring that file, not disabling verification.
Workflow warning text is clarified to describe the reused public TEST identity.
Existing configured private signing Secrets still take priority. A fresh signing
key is NOT silently generated in place of this repository's pinned key. Missing
or corrupted key material still stops the signing path with an explicit error.

Local release builds retain the previous explicit opt-in for using the public
key: `./gradlew assembleRelease -PaetherAllowPublicCiKey=true`. This does not
change GitHub's automatic path. Private local signing remains available.

## Security and installation continuity

This key is deliberately PUBLIC. Anyone with this source can sign with it, so it
provides test-build continuity, NOT authenticated production release identity.
Use a privately held signing identity for secure official distribution.
No private release key, local validation key or user configuration is included.
A previously installed APK signed by this same test key can retain its signer;
an APK signed by a DIFFERENT private key still needs that original private signer
for an in-place update. No claim is made that every installed APK will accept it.
The signer guard, certificate pin and optional private-key priority remain enabled.

## Executed verification

- `python3 scripts/test-auto-signing.py`: **5 passed**, running the exact workflow
  shell body with no Secrets, checking the actual certificate pin, optional private
  signing branch, missing/corrupt key refusal, and retained fingerprint comparison.
- Actionlint, 21 security/CI-helper regressions and 11 functional-wiring checks passed.
- Final signed-build rerun and APK certificate checks: see the appended result below.

These checks concern automatic signing. They do not assert that every external
service or the full remote GitHub native build will always succeed. Earlier
functional limits (including hosted V2Ray → Aether) remain in
`../TAB_AI_CHAIN_VALIDATION.md`. No full Android native/TUN/device test or remote
GitHub Actions run is claimed. Locally generated APKs are compiler/packaging checks
only and are NOT delivered as runnable VPN builds.

---

<div dir="rtl" align="right">

## راهنمای فارسی — بیلد خودکار مثل نسخه‌های قبلی

همان کلید تستی **عمومی قبلی** به پروژه برگشته است؛ کلید جدیدی ساخته نشده و اثرانگشت واقعی آن با اثرانگشت ثبت‌شدهٔ قبلی برابر است. نسخهٔ برنامه همچنان **1.6.0** است.

### چه کاری انجام بدهید؟

۱. ZIP را استخراج و محتویات پوشهٔ `Aether-1.6.0` را در ریشهٔ مخزن آپلود کنید؛ پوشهٔ مخفی `.github` و فایل کلید تستی داخل آن را جا نیندازید. کل پوشهٔ پروژه را یک لایه پایین‌تر از ریشه قرار ندهید.

۲. مثل قبل به شاخهٔ main/master پوش کنید یا از Actions ورک‌فلو Build APK را اجرا کنید.

۳. **برای این بیلد تستی نیازی به وارد کردن دستی چهار Secret نیست.** ورک‌فلو کلید تستی موجود را خودکار استفاده می‌کند. اگر از قبل Secretهای امضای خصوصی تنظیم شده باشند، همان مسیر خصوصی اولویت دارد.

محافظ تطبیق امضا غیرفعال نشده است؛ نبودن یا خرابی کلید همچنان خطای صریح می‌دهد تا امضای نسخه بدون اطلاع عوض نشود. علت توقف امضای بستهٔ قبلی، حذف فایل کلید بود و با بازگرداندن همان فایل اصلاح شده است.

### نکتهٔ امنیتی و نصب روی نسخهٔ قبلی

این کلید عمومی فقط برای تست و حفظ پیوستگی بیلدهای تستی است؛ برای تضمین اصالت انتشار رسمی امن نیست. اگر برنامهٔ نصب‌شده با همین کلید تستی قبلی امضا شده باشد، امضا یکسان می‌ماند؛ اگر با کلید خصوصی دیگری امضا شده باشد، برای نصب به‌صورت به‌روزرسانی همچنان همان کلید اصلی لازم است. کلید خصوصی انتشار، کانفیگ‌های خصوصی، لاگ‌های خام و کلید موقت محلی داخل بسته نیستند.

پنج تست مسیر امضا، تست‌های کمکی امنیت/CI و بررسی سینتکس ورک‌فلو موفق بودند. نتیجهٔ نهایی بیلد و کنترل امضای APK در بخش نتیجهٔ تکمیلی همین فایل ثبت می‌شود. بیلد بومی کامل اندروید، تست واقعی VPN روی موبایل و اجرای CI از راه دور ادعا نشده‌اند. محدودیت اتصال V2Ray به اتر مطابق گزارش قبلی هنوز برقرار است.

</div>

## Final automatic-signing build result

The actual no-Secrets workflow step was executed locally, and its automatically
exported signing variables were used for `testDebugUnitTest testReleaseUnitTest
assembleRelease` on the byte-identical application/native source checkout.
**BUILD SUCCESSFUL in 6m 55s** (21 tasks executed, 65 up-to-date).
Both Debug and Release: **377 passed, 1 intentionally skipped, 0 failures/errors**.
All four packaged APK certificates match the previous pinned CI signer, and all
four passed signature verification and 16 KiB zip alignment. VersionName remains
1.6.0, base versionCode 19. These are local packaging checks, not runnable VPN APKs
with rebuilt Android native engines and not a remote GitHub Actions execution.
See `../validation/current/automatic-signing-results.json`.

<div dir="rtl" align="right">

### نتیجهٔ نهایی بیلد با امضای خودکار
بیلد با خروجی واقعی مرحلهٔ امضای بدون Secret، در **۶ دقیقه و ۵۵ ثانیه موفق** شد. تست‌های Debug و Release هرکدام **۳۷۷ موفق، یک اجرا‌نشده و بدون شکست** بودند. امضای هر چهار APK تولیدشده با اثرانگشت کلید تستی قبلی یکسان بود و کنترل امضا و هم‌ترازی ۱۶ کیلوبایتی گذشت. نسخه همچنان **1.6.0** است. این نتیجه مربوط به بیلد و بسته‌بندی محلی است؛ بیلد بومی کامل اندروید یا اجرای واقعی گیت‌هاب ادعا نشده است.

</div>
