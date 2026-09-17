# گزارش M3 — سکتور واقعی کمپین و موجودی واقعی core

تاریخ: ۲۰۲۶-۰۹-۱۷. فقط پروژهٔ PC حاضر تغییر کرده است. کارهای commitنشدهٔ M2 حفظ شدند؛ commit/push انجام نشد. سه قابلیت محدود F1/F2/F3 اجرا شدند، نه کمپین مشترک قابل‌بازی.

## نتیجهٔ دقیق

- F1: `Vars.world.loadSector(SectorPresets.groundZero.sector)` محتوای بسته‌بندی‌شدهٔ `maps/serpulo/groundZero.msav` را در ServerLauncher واقعی v160.4 بارگذاری کرد. `state.isCampaign=true`، `serpulo:170`، preset برابر `groundZero`، ابعاد ۲۵۶×۲۵۶ و core واقعی ثبت شدند. هیچ نقشهٔ عمومی با پرچم ساختگی کمپین جایگزین نشده است.
- F2: کد production در `src/sc/CampaignInventory.java` موجودی `Items.copper` را مستقیم از `core.items` می‌خواند و روی thread موتور کم می‌کند. فقط fixture مقدار آغازین واقعی ۰ را به ۱۰۰ تنظیم کرد. رزرو ۳۰ مسی با ID جدید، موجودی را ۱۰۰→۷۰ کرد؛ retry همان ID موجودی را در ۷۰ نگه داشت. درخواست رزرو ۸۰ مسی به علت funds رد شد و هیچ تغییری نداد. قیمت صرفاً کاتالوگ میزبان است؛ درخواست قیمت ندارد.
- F3: دو JVM واقعی با JAR یکسان و راه‌اندازی ترتیبی هم‌زمان فعال بودند. کنترلر فقط payload چاپ‌شدهٔ موتور را به stdin موتور دیگر رساند. رزرو جدید مهمان موجودی واقعی میزبان را ۷۰→۴۰ کرد؛ پیش از تحویل پاسخ، کش مهمان هنوز ۷۰ بود. پس از پاسخ کش ۴۰ شد. retry دوباره کم نکرد؛ پاسخ تاریخی واقعی میزبان به علت stale رد شد.
- دو سکتور نیز واقعاً بارگذاری شدند: میزبان Ground Zero با ID 170 و مهمان Frozen Forest از `maps/serpulo/frozenForest.msav` با ID 64 و ابعاد ۲۰۰×۲۰۰. هر دو `campaign=true` و `netActive=false` داشتند. موجودی واقعی مس مهمان قبل و بعد **صفر** بود؛ هیچ موجودی میزبان در core مهمان کپی نشد. کش مهمان از core محلی جداست.

این عملیات «رزرو آزمایشی» است، نه unlock پژوهش vanilla. هر ID موفق تازه یک رزرو دیگر می‌خرد؛ تکرار همان ID فقط پاسخ تاریخی همان تراکنش را می‌دهد.

## آزمون‌اول: RED / GREEN

همهٔ مسیرهای جدول زیر زیر `build/` هستند و پسوندهای `-result.json` و `-host.log` و در آزمون‌های دوموتوره `-guest.log` دارند.

| قابلیت | RED واقعی قبل از پیاده‌سازی | GREEN |
|---|---|---|
| F1 | `m3-f1-red`، 26.636s: موتور با JAR موجود بالا آمد اما فرمان fixture سکتور وجود نداشت | `m3-f1-green`، 9.761s |
| F2 | `m3-f2-red`، 14.351s: سکتور واقعی بارگذاری شد ولی فرمان میزبان M3 وجود نداشت | `m3-f2-green`، 12.646s؛ ۱۴ ورودی منفی |
| F3 | `m3-f3-red`، 23.913s: F1/F2 پاس، شروع نقش guest در پیاده‌سازی host-only رد شد | `m3-f3-green`، 22.423s؛ مهمان در منو |
| زیرآزمون دو سکتور F3 | `m3-f3-sectors-red`، 39.916s: فرمان fixture جنگل هنوز نبود | `m3-f3-sectors-green`، 21.937s |

اولین تلاش F1 در `m3-f1-attempt1` واقعاً سکتور را بارگذاری کرد ولی انتظار نادرست تست برای ID=15 شکست خورد؛ موتور مقدار 170 را داد. انتظار تست اصلاح شد، نه دادهٔ موتور. production هیچ ID ثابت 170 ندارد و سکتور جاری را bind می‌کند. یک خطای کامپایل ناشی از حذف اتفاقی import در افزودن fixture جنگل نیز فوراً با بازگرداندن import رفع شد؛ آن خطا RED قابلیت محسوب نشده است. فایل موقت نتیجهٔ آن تلاش با GREEN جایگزین شد؛ ادعای حفظ artifact مجزا برای آن نداریم.

دو JAR پیشین نگهداری شده‌اند: `m3-pre-f2.jar` و `m3-pre-f3.jar`. فایل `m3-f1-red` از SmokeLauncher قبلی با گزینهٔ `--baseline` استفاده کرد؛ خطای نبود artifact نبود.

## پذیرش نهایی، دقیقاً همین JAR

`build/m3-acceptance-result.json`: **passed=true، 21.410s**، دو سکتور واقعی.

- PID میزبان: 3428315؛ PID مهمان: 3428389؛ هر دو خروج 143 پس از SIGTERM کنترل‌شدهٔ فرزند خود آزمون، نه ادعای shutdown تمیز.
- میزبان هنگام تبادل: tick از 602.2972263153642 به 775.2926904018968، updateId از 523 به 696؛ سکتور 170 ثابت و مس ۷۰→۴۰.
- مهمان: tick از 49.78521704673767 به 242.17393707484007، updateId از 6 به 198؛ سکتور 64 ثابت، مس واقعی ۰→۰.
- بیشینهٔ RSS نمونه‌برداری‌شده: میزبان 139.785 MiB و مهمان 121.891 MiB. کمینهٔ RAM آزاد در نمونه‌های monitor برابر 635.461 MiB و بیشینهٔ load5 برابر 2.836 بود. این‌ها نمونه‌اند، نه تضمین ثبت تمام قله‌ها. guard کامپایل جداگانه نیز فعال بود و خروجی آن در console ثبت شد.
- دقیقاً دو `SC_M3_DEBIT` در لاگ میزبان وجود دارد. مقدار before/after و `engineThread=true` حاصل بررسی هویت thread در production است؛ query مستقل fixture هم مقدار core را بررسی کرده است.
- ۱۴ منفی میزبان: shape، protocol، version، session، ID، سیاره/سکتور اشتباه، قیمت تزریقی، catalog ناشناخته، replay conflict، type، حد طول رشته/UTF-8، funds. پس از هرکدام core واقعاً query شد و روی ۷۰ ماند.
- مهمان ۵ ورودی منفی برای ID بدون pending، سکتور، session، operation و revision conflict را رد کرد؛ به‌علاوه پاسخ قدیمی واقعی میزبان stale شد. پس از ردها کش روی ۴۰ ماند.

فریم‌های موفق نمونه؛ پاسخ‌ها ساختهٔ موتور میزبان‌اند:

```text
SC3|1|session-m3|r2|REQ|serpulo|170|reserve-copper
SC3|1|session-m3|r2|RES|serpulo|170|reserve-copper|2|40
```

آزمون پیش‌فرض نهایی بدون تغییر دیگر harness: `build/m3-result.json`، **passed=true، 20.856s**، مهمان در منو.

رگرسیون M2 **فقط یک‌بار** با `--maps --label m3-m2-regression`: **passed=true، 30.677s، ۲۳ منفی**؛ replay پس از restart مهمان، تیک نقشه‌های تولیدشده و بقای میزبان پاس شدند. این رگرسیون پیشرفت جدید کمپین محسوب نشده است. `tests/smoke.py` نیز روی همان JAR پاس شد و موتور خروج تمیز 0 داد؛ صرفاً bootstrap regression است.

## artifact و جداسازی fixture

JAR: `build/shared-campaign-pc.jar`

```text
SHA-256: 128f2002b02f672b214d9b4eeb91af2c16dc959a127ff7295551acb81241bdea
Engine:  0dbd3275402ff6df8367620a47e0b24e4d4547332b99af7c434796520ca484c2
```

محتویات JAR با ZIP inspection بررسی شد: فقط `sc/SharedCampaignMod.class`، `sc/StdioLedger.class`، `sc/CampaignInventory.class` و `mod.json`. همهٔ کلاس‌های لازم production موجودند. `CampaignLauncher`، `SmokeLauncher`، fixture seed و کد بارگذاری world در JAR نیستند. metadata نسخهٔ 0.0.2 و متن قدیمی synthetic-only حفظ شده‌اند؛ برای شناسایی artifact M3 از digest استفاده شود. README رفتار جدید و محدودیت metadata را توضیح می‌دهد.

M2/SC2 دست‌نخورده و ساختگی باقی ماند. M3/SC3 ساختار مشابه فرمان stdin و marker stdout دارد ولی عمداً پروتکل جدا و ناسازگار با فریم SC2 است؛ موجودی M2 هیچ منبع داده‌ای برای M3 نیست.

## مرزها و کارهای انجام‌نشده

- مانع بارگذاری کمپین مشاهده نشد؛ هم Ground Zero و هم Frozen Forest در heap موجود جا شدند. heap افزایش نیافت.
- fixture صرفاً `logic.reset/loadSector/play` را به کار می‌گیرد و برای آزمون کوتاه waves/game-over را خاموش می‌کند. launch flow معمول، UI، بازیکن و save شخصی آزمایش نشده‌اند.
- authority عملیات M3 فقط core میزبانِ bindشده است. کد تغییر سکتور یا تعویض core را رد می‌کند؛ تست جابه‌جایی خود میزبان/تعویض core اجرا نشده و ادعای اثبات آن نداریم. ثابت ماندن هویت میزبان در تبادل اثبات شده است.
- شناسهٔ session احراز هویت نیست. console/controller باید قابل‌اعتماد باشد؛ هیچ رمزنگاری، شبکهٔ راه‌دور، socket/listener یا reconnect خودکار وجود ندارد.
- تاریخچهٔ موفق‌ها حداکثر ۲۵۶ ID، موقت و در RAM است. شکست‌ها به‌عنوان تراکنش دائمی ثبت نمی‌شوند. قطع میزبان تاریخچه را از بین می‌برد؛ crash atomicity و persistence اثبات نشده‌اند.
- revision فقط رزروهای این mod را می‌شمارد؛ mining، spending، import/export عادی را رصد نمی‌کند. کش مهمان snapshot دائماً تازه نیست؛ retry پاسخ قدیمی همان ID را بازمی‌گرداند.
- همگام‌سازی عمومی موجودی همهٔ سکتورها، research واقعی، planet/tech UI، save/network synchronization و کمپین مشترک قابل‌بازی تحویل نشده است. core صفرِ مهمان نشان می‌دهد duplication رخ نداده، نه اینکه اقتصاد vanilla کاملاً یکپارچه شده باشد.

## ایمنی و بازتولید مستقل

فقط همین پروژه محل نوشتن بود؛ engine در `/opt/mindustry/server-release.jar` فقط خوانده شد و engine دسکتاپ تغییر نکرد. همهٔ داده‌های JVM در disposable directoryهای زیر build بودند و پاک شدند. guard مشارکتی Java 17 بدون تغییر: منع write بیرون پوشهٔ disposable، deletion در JVM، subprocess و listen/connect/multicast. این guard sandbox کد خصمانه نیست. هشدار JLine/infocmp و SecurityManager در لاگ‌ها حفظ شده‌اند. نصب، دانلود، GUI، پورت جدید، سرویس، فایل شخصی، credential، commit و push انجام نشد.

موتورها nice10، Xmx128m، ActiveProcessorCount1؛ javac96m؛ smoke قدیمی Xmx160m. RAM≥200MiB و load5≤4 در guard حفظ شدند؛ deadline هر M3 از پیش از کامپایل ۸۰ ثانیه به‌علاوه حداکثر ۸ ثانیه cleanup دو فرزند است. فقط فرزندان متعلق به آزمون در finally متوقف شدند.

از ریشهٔ همین پروژه، ترتیبی اجرا کنید؛ این‌ها pytest نیستند:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/build.py
PYTHONDONTWRITEBYTECODE=1 python3 tests/m3_campaign.py --two-sectors --label independent-m3
sha256sum build/shared-campaign-pc.jar
```

برای مصرف کمتر، `--two-sectors` را حذف کنید؛ مهمان در منو می‌ماند. برای یک موتور از `--stage f1` یا `--stage f2` استفاده کنید. برای بازتولید RED مورد انتظار (خروج 1) می‌توان `--stage f2 --jar build/m3-pre-f2.jar --label independent-m3-f2-red` یا `--stage f3 --jar build/m3-pre-f3.jar --label independent-m3-f3-red` را اجرا کرد. JAR buildشده دوباره ممکن است به علت timestamp بسته‌بندی digest متفاوت داشته باشد؛ JSON هر run digest واقعی کپی‌شده به دو موتور را ثبت می‌کند.

بازبینی، انتشار و تأیید مستقل بر عهدهٔ parent است.
