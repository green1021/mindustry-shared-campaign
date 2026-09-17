# گزارش اجرای بوت‌استرپ PC — ۲۰۲۶-۰۹-۱۷

## نتیجه
موفق، **فقط در محدودهٔ بوت‌استرپ مود Java برای PC**. فایل اجرایی ساخته و توسط موتور واقعی Mindustry v160.4 در حالت headless بارگذاری و اجرا شد. هیچ قابلیت کمپین مشترک پیاده‌سازی نشده است.

- خروجی: `build/shared-campaign-pc.jar`
- اندازهٔ مشاهده‌شده: **1404 بایت**
- نسخهٔ مود: `0.0.1`
- تنها اعضای JAR: `sc/SharedCampaignMod.class` و `mod.json`
- نسخهٔ class: `61`، معادل Java 17؛ بدون تست، fixture، وابستگی یا asset موتور.
- metadata: `java: true`، `minGameVersion: "160.4"`، `main: "sc.SharedCampaignMod"`.

SHA-256 خروجی:
```text
c565b93732de0bdd77983862b37bb98061b53b4b4eb8eb2106af257e393e30e7
```
SHA-256 موتور فقط‌خواندنی `/opt/mindustry/server-release.jar`:
```text
0dbd3275402ff6df8367620a47e0b24e4d4547332b99af7c434796520ca484c2
```
SHA-256 فیکسچر منفی جداگانه:
```text
4035b82788cb8df10cfd8b78f968efb591a3c2fa33eb4aa31638ec15c8209f99
```

## دستورهای واقعاً اجراشده و وضعیت خروج
همه از مسیر `/root/hermes-workspace/projects/mindustry-shared-campaign`، مگر بررسی‌های فقط‌خواندنی اولیه.

| دستور | خروج | نتیجه |
|---|---:|---|
| `java -version` و `javac -version` | 0 | OpenJDK/Javac 17.0.20 |
| `PYTHONDONTWRITEBYTECODE=1 python3 scripts/build.py` | 0 | کامپایل و بسته‌بندی تولید |
| `PYTHONDONTWRITEBYTECODE=1 python3 tests/smoke.py`، اولین اجرای JAR | 1 | خطای JLine/OSUtils پیش از init؛ گارد subprocess مانع `File.canExecute` شد |
| همان smoke پس از تنظیم provider/encoding ساده | 1 | هنوز کشف اجرایی‌های PATH توسط OSUtils شکست می‌خورد |
| همان smoke پس از حذف PATH از محیط فرزند و تعیین terminal type | 0 | موتور 0؛ همهٔ assertionها موفق |
| `PYTHONDONTWRITEBYTECODE=1 python3 tests/negative.py` | 0 | smoke داخلی 1، موتور 0؛ شکست دقیق assertion نشانگر تأیید شد |
| `PYTHONDONTWRITEBYTECODE=1 python3 tests/smoke.py >/dev/null 2>&1` | 0 | بازآزمایی نهایی موفق؛ لاگ موتور همچنان در فایل ثبت شد |
| `sha256sum build/shared-campaign-pc.jar build/negative-fixture.jar /opt/mindustry/server-release.jar` | 0 | هش‌های فوق |
| `stat -c '%n %s bytes' build/shared-campaign-pc.jar` | 0 | 1404 بایت |
| بررسی با Python `zipfile` و `struct` | 0 | دقیقاً دو عضو؛ class major=61 |

گزارش RED اولیهٔ موجود هنگام شروع فقط `Expected built mod JAR is missing` بود؛ آن را اثبات کافیِ منفی محسوب نکردیم. لاگ‌های دو شکست JLine در خروجی اجرای ابزار مشاهده شدند؛ فایل smoke.log با اجرای موفق بعدی جایگزین شد.

دستور واقعی کامپایل تولید، با مسیرهای مطلق پروژه:
```sh
nice -n 10 javac -J-Xmx96m -J-XX:ActiveProcessorCount=1 --release 17 \
  -cp /opt/mindustry/server-release.jar \
  -d /root/hermes-workspace/projects/mindustry-shared-campaign/build/classes \
  /root/hermes-workspace/projects/mindustry-shared-campaign/src/sc/SharedCampaignMod.java
nice -n 10 jar -J-Xmx96m -J-XX:ActiveProcessorCount=1 --create --no-manifest \
  --file /root/hermes-workspace/projects/mindustry-shared-campaign/build/shared-campaign-pc.jar \
  -C /root/hermes-workspace/projects/mindustry-shared-campaign/build/classes sc/SharedCampaignMod.class \
  -C /root/hermes-workspace/projects/mindustry-shared-campaign mod.json
```

## شواهد موتور واقعی
متن لاگ پس از حذف کدهای رنگ ANSI، نه تغییر محتوا:
```text
SC_NETWORK_DENIED
1 mods loaded.
SC_PC_INIT_OK engine=160.4
Server loaded. Type 'help' for help.
SC_STATUS version=0.0.1 engine=160.4 features=false
Shutting down server.
```
`sc-status` از stdin به کنسول موتور ارسال شد؛ پاسخ آن صرفاً diagnostics است. کد تولید فقط وقتی **فیلدهای واقعی** `Version.build == 160` و `Version.revision == 4` هستند نشانگر موفقیت می‌دهد؛ در غیر این صورت warning با `SC_PC_VERSION_MISMATCH` و نسخهٔ واقعی صادر می‌شود. مسیر mismatch روی موتور نسخهٔ دیگر اجرا نشده است؛ این محدودیت صریح است.

فیکسچر منفی از سورس تولید در `build/negative-fixture` کامپایل شد و فقط متن نشانگر آن به `SC_PC_INIT_OLD engine=160.4` تغییر کرد. JAR تولید دست‌نخورده ماند. موتور آن را بارگذاری کرد و خروج 0 داشت، اما smoke خروج 1 با خطای زیر ثبت کرد:
```text
Real mod init/version marker missing
NEGATIVE_ASSERTION_CONFIRMED smoke_exit=1 engine_exit=0
```
بنابراین فقط وجود JAR یا خروج موفق موتور نمی‌تواند تست را سبز کند.

## رفع اشکال harness بدون تضعیف گارد
`tests/SmokeLauncher.java` تغییر نکرد. تنظیمات JLine برای ترمینال dumb، UTF-8، بدون رنگ dumb، و **حذف PATH فقط از محیط فرزند موتور** افزوده شد. مسیرهای `/usr/bin/nice` و `/usr/bin/java` صریح هستند. OSUtils دیگر در PATH به دنبال executable نمی‌گردد. HOME محیطی دستکاری نشد؛ `user.home`، `java.io.tmpdir` و cwd از ابتدا به پوشهٔ disposable اشاره دارند. خطای ثبت‌شده مربوط به executable discovery بود، نه مجوز نوشتن home.

تلاش بعدی JLine برای subprocess `infocmp` همچنان توسط گارد رد می‌شود و warning/stack trace در لاگ می‌ماند؛ JLine fallback می‌کند و موتور به‌درستی ادامه می‌دهد. اخطار deprecation مربوط به SecurityManager در Java 17 هم پنهان نشده است. هیچ dependency از classpath حذف و هیچ مجوز subprocess یا شبکه اعطا نشده است. assertion دقیق نشانگر حفظ شد؛ شرط مبهم loader به `1 mods loaded.` سخت‌تر شد و پاسخ واقعی command نیز بررسی می‌شود.

## حدود ایمنی و منابع
- موتور فقط از launcher محافظ اجرا شد؛ listen/connect/multicast، subprocess، deletion و نوشتن بیرون پوشهٔ آزمایش توسط گارد ممنوع است. این گارد تعاونی است، نه sandbox مقاوم در برابر کد خصمانه.
- config، cwd، home جاوا و temp در `build/smoke-*` disposable هستند؛ Python پس از پایان آن‌ها را پاک می‌کند. هیچ save شخصی استفاده نشد.
- JVMهای build/test ترتیبی: nice 10، `ActiveProcessorCount=1`، heap موتور 160 MiB و javac/jar برابر 96 MiB.
- پیش از کار سنگین و حین اجرای فرزندان RAM و load5 بررسی می‌شوند؛ آستانه‌ها RAM≥200 MiB و load5≤4. فقط فرزند متعلق به همان اجرا در صورت افت منابع/timeout متوقف می‌شود.
- timeout موتور 30 ثانیه و کامپایل harness 20 ثانیه است؛ اجراهای مشاهده‌شده زیر 60 ثانیه بودند. کل کار در سقف 15 دقیقه ماند.
- بررسی پایانی سیستم: available RAM برابر 758 MiB و load5 برابر 3.12.
- دانلود، نصب، پورت شنوندهٔ جدید، desktop/GPU، سرویس، Docker، تغییر پروژهٔ دیگر، دسترسی به credential، commit/push یا ساخت repository انجام نشد. `.gitignore` موجود تغییر نکرد.

## فایل‌های شواهد
- `build/build-result.json`: هش build و موتور
- `build/smoke-result.json`: نتیجهٔ نهایی، exit موتور، فرمان کامل JVM با مسیر disposable واقعی و نمونه‌های منابع
- `build/smoke.log`: لاگ خام واقعی موتور
- `build/negative-result.json` و `build/negative.log`: شکست موردانتظار روی نشانگر قدیمی
- `scripts/build.py`، `scripts/resources.py`، `tests/smoke.py`، `tests/negative.py`: اجرای قابل تکرار بدون دانلود

## محدودیت‌های دقیق
این خروجی فقط بوت‌استرپ مود Java برای PC را اثبات می‌کند. اجرای GUI دسکتاپ، import در کلاینت گرافیکی، دو بازیکن و شبکهٔ واقعی آزمایش نشده‌اند. سکتورهای مستقل هم‌زمان، کمپین مشترک میزبان، همگام‌سازی فناوری/منابع، انتخاب سکتور، persistence، reconnect و UI پیاده‌سازی نشده‌اند. `features=false` عمداً صریح است. اندروید به آینده موکول شده است. هدف نهاییِ کمپین مشترک همچنان فقط هدف آینده است، نه قابلیت این JAR.
