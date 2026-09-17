# گزارش واقعی M2 — آزمایش PC stdio، ۲۰۲۶-۰۹-۱۷

## نتیجه و محدوده
**F1 و F2 در محدودهٔ آزمایش تعیین‌شده موفق شدند؛ F3 نیز با fixture جدا اثبات شد.** این نتیجه به معنای کمپین مشترک قابل‌بازی نیست. دو فرآیند واقعی Mindustry 160.4 با یک JAR یکسان و guard موجود هم‌زمان زنده بودند؛ کنترلر Python فقط فریم خروجی موتور را به stdin موتور دیگر رساند. هیچ پاسخ موفق یا منطق ledger در کنترلر پیاده‌سازی نشد. ورودی‌های ساختگی فقط برای تست رد پیام نامعتبر استفاده شدند.

خروجی مود قابل بارگذاری: `build/shared-campaign-pc.jar`، نسخهٔ **0.0.2**، اندازهٔ **5097 بایت**.

SHA-256:
```text
4e95003d4a49bc5b23d10aa43cbcd2857c76e2f4d9953e0c2ffbb46ad03a3e09
```
SHA-256 موتور فقط‌خواندنی:
```text
0dbd3275402ff6df8367620a47e0b24e4d4547332b99af7c434796520ca484c2
```
اعضای بررسی‌شدهٔ JAR دقیقاً: `sc/SharedCampaignMod.class`، `sc/StdioLedger.class`، `mod.json`. نه کد domain قدیمی، نه fixture، نه asset یا وابستگی اضافه بسته‌بندی نشده‌اند.

## رفتار اثبات‌شده در موتور، نه در mock
اجرای پذیرش بدون تغییر JAR: `python3 tests/m2_link.py --label m2-acceptance`، خروج **0**، زمان **25.982 ثانیه**.

| نقش | PID واقعی |
|---|---:|
| میزبان | 3420243 |
| مهمان اولیه | 3420286 |
| مهمان پس از راه‌اندازی دوباره | 3420361 |

هر سه کپی JAR هش بالا را داشتند. میزبان و مهمان ترتیبی boot شدند ولی هنگام انتقال فریم هر دو زنده بودند. موجودی و unlock کاملاً **synthetic test state** هستند؛ نه آیتم‌های vanilla، نه پژوهش واقعی.

فریم‌های واقعاً دریافت و ارسال‌شده در لاگ/JSON:
```text
guest → host: SC2|1|session-m2|r1|REQ|test-tech
host → guest: SC2|1|session-m2|r1|RES|1|70|true
```
- قبل از انتقال درخواست، میزبان ۱۰۰ و مهمان unknown (`-1`) گزارش کردند.
- پس از پردازش درخواست و پیش از انتقال پاسخ، میزبان ۷۰ و مهمان همچنان unknown بود.
- پس از انتقال پاسخ، هر دو دستور موتور: `balance=70 revision=1 unlocked=true`.
- ارسال مجدد همان درخواست و پاسخ، شارژ دوم ایجاد نکرد.
- قیمت `test-tech` فقط در کاتالوگ میزبان ۳۰ است. فریم درخواست دارای price اضافی با `reason=shape` رد شد.
- فناوری آزمایشی دوم `test-tech-2` با قیمت میزبان ۱۰، پاسخ واقعی زیر را تولید کرد:
```text
SC2|1|session-m2|r2|RES|2|60|true
```
- پس از revision 2، بازپخش پاسخ قدیمی واقعی revision 1 با `reason=stale` رد شد؛ موجودی مهمان ۶۰ ماند.
- مهمان عمداً terminate شد؛ میزبان دوباره پاسخ state داد و زنده ماند.
- مهمان تازه با همان session و درخواست‌های r1 و r2، پاسخ‌های تاریخی همان میزبان را دریافت کرد؛ میزبان دوباره شارژ نشد. این **replay صریح** است، نه reconnect خودکار، snapshot آخرین وضعیت یا persistence. مهمان تازه در فاصلهٔ replay اول و دوم، وضعیت تاریخی ۷۰ را دارد و سپس به ۶۰ می‌رسد.

۲۳ بررسی منفی در اجرای نهایی ثبت شد: فریم خراب، پروتکل/نسخه/session/ID نامعتبر، قیمت اضافی، فناوری ناشناخته، تضاد ID، نوع پیام نادرست، اندازهٔ بیش از ۴۰۹۶، حد بایتی UTF-8، ورودی دقیقاً ۴۰۹۶ بایت با ID غیرمجاز، فعال‌سازی دوباره، نقش نامناسب، پاسخ ناخواسته، مقدار غیرعددی، تضاد revision و پاسخ stale. بعد از **هر** رد، state همان موتور query و عدم تغییر و زنده‌بودن بررسی شد؛ نبودن FRAME/APPLIED در بازهٔ ورودی منفی نیز assertion دارد. علاوه بر این، غیرفعال‌بودن پیش‌فرض هر دو مود بررسی شد.

## TDD و شکست‌های واقعی، جدا از GREEN
| مرحله و فرمان | خروج | شاهد |
|---|---:|---|
| `python3 tests/m2_link.py --jar build/m2-old.jar --label m2-red --stage core` | 1 | دو موتور PIDهای 3417157 و 3417198 با JAR قدیمی واقعاً boot شدند؛ نشانگر `SC_M2_READY role=host session=session-m2 synthetic=true` وجود نداشت |
| build پس از RED و `--label m2-core-green` | 0 | نخستین انتقال واقعی ۱۰۰→۷۰ و retry |
| `--label m2-negative --stage negative` | 0 | شاخه‌های رد اولیه بدون crash |
| `--label m2-replay-red --stage replay` | 1 | `test-tech-2` هنوز ناشناخته بود؛ میزبان پاسخ FRAME تولید نکرد؛ قبل از افزودن آیتم دوم |
| build و `--label m2-green` | 0 | revision دوم، stale واقعی، قطع مهمان و replay جدید |
| `--maps --stage core --label m2-maps-red` | 1 | دستور map fixture هنوز وجود نداشت؛ `SC_FIXTURE_LOADED id=generated-host` غایب بود |
| افزودن `tests/TickingLauncher.java` و `--maps --label m2-maps-green` | 0 | tick واقعی دو نقشه پیش رفت |
| build نسخهٔ 0.0.2، تست پیش‌فرض و `--maps --label m2-maps` | 0 / 0 | artifact نهایی یکسان، 25.178 و 28.024 ثانیه |
| اجرای مجدد بدون تغییر `--label m2-acceptance` | 0 | پذیرش نهایی، 25.982 ثانیه |

دو خطای harness هم رخ داد و پنهان نشد: ابتدا assertion loader روی متن خام ANSI شکست خورد؛ در `build/m2-harness-red-*` نگه داشته شد و RED قابلیت حساب نشد. یک محاسبهٔ طول fixture منفی اشتباه بود؛ `build/m2-negative-harness-error.json` حفظ شد و طول دقیق قبل از اجرای منفی اصلاح شد. RED اصلی با دو موتور روی JAR موجود دوباره اجرا و جدا ذخیره شد. شکست ساختگی بر پایهٔ نبود فایل یا mock وجود ندارد. فرمان‌ها از ریشهٔ همین پروژه اجرا شدند؛ متغیر `PYTHONDONTWRITEBYTECODE=1` در فرمان‌های بازتولید توصیه می‌شود.

## F3: نقشه‌های تولیدشده در دو موتور هم‌زمان
`build/m2-maps-result.json`، زمان **28.024 ثانیه**، همان هش نهایی JAR.

| موتور / PID | شناسه و اندازهٔ نقشه | tick قبل → بعد | updateId قبل → بعد |
|---|---|---|---|
| میزبان / 3419969 | generated-host، 16×16 | 9.928667392581701 → 111.89303940162063 | 7 → 109 |
| مهمان / 3420009 | generated-guest، 20×16 | 10.179673418402672 → 112.20257361233234 | 6 → 104 |

در هر دو نمونه: `playing=true campaign=false netActive=false`. زمین میزبان stone و مهمان sand است و هر نقشه یک core دارد. fixture از `Vars.world.loadGenerator` و `Vars.logic.play` استفاده می‌کند؛ هیچ افزایش دستی tick/updateId ندارد. نمونهٔ قبل از انتقال درخواست و بعد از درخواست/پاسخ و retry گرفته شد. این فقط **generated maps** است، نه فایل map واردشده، نه دو سکتور کمپین، نه دو بازیکن GUI. مهمانِ مجدداً راه‌اندازی‌شده در بخش replay این تست، نقشهٔ تازه load نمی‌کند؛ ادعای tick مربوط به دو موتور اولیه است.

## پروتکل، منابع و ایمنی
- پروتکل سادهٔ delimiter-based با شناسهٔ `SC2` و نسخهٔ `1`؛ JSON لازم نشد و dependency اضافه نشد. فریم حداکثر ۴۰۹۶ بایت UTF-8؛ شکل/نوع/نسخه/session/ID/مقادیر بررسی می‌شوند. هیچ deserialization، reflection، eval، exec یا socket در مسیر مود جدید وجود ندارد.
- سابقهٔ هر موتور حداکثر ۲۵۶ ID؛ session و role بعد از شروع قابل تعویض نیست. پذیرش پاسخ فقط برای ID pending با session صحیح و revision غیرکاهشی؛ revision برابر فقط با دادهٔ مساوی. marker `APPLIED` بعد از تغییر واقعی کش چاپ می‌شود، نه هنگام صرفاً دریافت ورودی.
- trusted local console مرز اعتماد است؛ session امضا یا احراز هویت نیست. این کد برای انتشار کنسول روی شبکه آماده نیست.
- guard قبلی `SmokeLauncher.java` بدون تغییر استفاده شد: منع listen/connect/multicast، subprocess، حذف و write بیرون پوشهٔ disposable موتور. این guard تعاونی است، نه sandbox مقاوم به کد خصمانه.
- هیچ دستور عادی `host`، listener یا پورت جدیدی استفاده نشد. `TickingLauncher` فقط در test classpath است و همان launcher محافظ را فراخوانی می‌کند.
- javac و jar با heap ۹۶ MiB؛ هر موتور heap ۱۲۸ MiB، `ActiveProcessorCount=1` و nice 10. موتورهای میزبان و مهمان هم‌زمان resource-monitor شدند؛ PIDهای زنده در نمونه‌ها ثبت شده‌اند.
- پذیرش نهایی: کمینهٔ RAM available **708.223 MiB**، بیشینهٔ load5 **2.58545**. تست نقشه: کمینهٔ **469.555 MiB**، بیشینهٔ load5 **2.67432**؛ بالاتر/پایین‌تر از آستانه‌های مجاز ۲۰۰ MiB / ۴.
- deadline کل تست از قبل از compile شروع می‌شود: ۸۰ ثانیه برای کار، cleanup محدود برای دو فرزند زنده. هیچ تست به ۹۰ ثانیه نرسید. finally فقط Popenهای متعلق به همان اجرا را متوقف و پوشه‌های خودش را پاک می‌کند.
- exit ثبت‌شدهٔ JVMها **143** است، چون تست آگاهانه SIGTERM می‌فرستد؛ ادعای shutdown تمیز موتور با exit0 نداریم. خروج ۰ Python حاصل assertionهای واقعی است.
- stderr و warningهای JLine/infocmp و SecurityManager در لاگ خام حفظ‌اند. PATH فقط از محیط فرزندان حذف شده است.
- هیچ download/install، تغییر سرویس یا Docker، credential، save اصلی، پروژهٔ دیگر، پورت، Android، GUI، commit/push یا عملیات GitHub انجام نشد.

## فایل‌ها و فرمان بازتولید مستقل
```sh
cd /root/hermes-workspace/projects/mindustry-shared-campaign
PYTHONDONTWRITEBYTECODE=1 python3 scripts/build.py
PYTHONDONTWRITEBYTECODE=1 python3 tests/m2_link.py
PYTHONDONTWRITEBYTECODE=1 python3 tests/m2_link.py --maps --label m2-maps
sha256sum build/shared-campaign-pc.jar
```
این‌ها script مستقل هستند، نه pytest. build ممکن است به‌دلیل timestamp بسته‌بندی هش متفاوتی تولید کند؛ معیار تست، برابری دو کپی با JAR همان اجرا است. خروج منابع/فرمان‌ها واقعی چاپ و در JSON ثبت می‌شود.

شواهد حفظ‌شده: `build/m2-red-result.json` و لاگ‌های RED، `build/m2-old.jar`، `build/m2-replay-red-*`، `build/m2-maps-red-*`، `build/m2-result.json`، `build/m2-acceptance-result.json` و سه لاگ آن، `build/m2-maps-result.json` و سه لاگ آن، `build/build-result.json`. JSONها exact received/relayed frames، PID، فرمان JVM و نمونه‌های منابع دارند. artifacts در build gitignored هستند؛ سورس و این گزارش قابل نگهداری در مخزنند. تست‌های bootstrap قدیمی برای سازگاری نسخه/کلاس جدید تنظیم شده‌اند ولی در این مأموریت به عنوان پیشرفت تازه تکرار نشدند.

## آنچه باقی مانده است
کمپین قابل‌بازی PC، منابع واقعی سکتور، research واقعی، save synchronization و integrity، ذخیرهٔ پایدار و recovery میزبان، reconnect خودکار و snapshot آخرین وضعیت، انتخاب سکتور و UI سیاره، شبکهٔ واقعی، دو کاربر گرافیکی و Android هنوز وجود ندارند. فلگ `unlocked` فقط به `test-tech` آزمایشی اشاره دارد؛ پروتکل کل tech tree را همگام نمی‌کند. هدف M2 انتقال واقعی state آزمایشی میان دو engine process بود؛ این هدف اثبات شد، ولی هدف نهایی محصول هنوز انجام نشده است.
