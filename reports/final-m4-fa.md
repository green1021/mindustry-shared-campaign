# گزارش M4 — انتخاب سکتور میزبان و ذخیره‌های جدا، نه کیف پول مشترک

تاریخ: ۲۰۲۶-۰۹-۱۷. نتیجهٔ نهایی **F1/F2 در محدودهٔ محلی آزمایشی پاس شدند**؛ F3 شامل تست قابل‌بازتولید، JAR و مستندات تکمیل شد. کمپین مشترک قابل‌بازی، GUI، شبکه یا اتصال بازیکن ادعا نمی‌شود. تغییرات commitنشدهٔ M2/M3 حفظ شدند؛ فایل‌های منطق و تست M3 بازنویسی نشدند.

## اصلاح هدف اقتصادی

هر نقشه/سکتور موجودی خودش را دارد. هیچ تجمیع دائمی core، کیف پول مشترک یا debit دلخواه بین سکتورها در M4 پیاده نشده است. آزمایش‌های reservation در M3 و ledger ساختگی M2 تاریخچهٔ آزمایش‌اند، نه اقتصاد مطلوب بازی. مسیر M4 آن‌ها را فراخوانی نمی‌کند.

قراردادهای vanilla و لینک‌های نسخه‌دار **یک بار** در `reports/m4-vanilla-contract.md` ثبت شدند: موجودی live/inactive در Sector، تجمیع پژوهش و ترتیب برداشت، legacy LaunchPad در برابر LandingPad و import timers، و شبیه‌سازی Universe. مسیریابی سکتور فعال راه‌دور و snapshot منسجم پژوهش هنوز آینده‌اند.

**محدودیت عمدی:** فعال‌سازی M4، اجرای offline turn را در میزبان و worker متوقف می‌کند. این راه‌حل نهایی شبیه‌سازی توزیع‌شده نیست و اقتصاد دیگری جای vanilla نمی‌گذارد. تست force-turn واقعاً `before=0 after=0` و marker توقف را ثبت کرد؛ ticking جهان زنده ادامه یافت. فعال‌سازی فقط headless v160.4 و workspace یک‌بارمصرف را قبول می‌کند. نصب عادی و مسیر M3 تغییری در Universe نمی‌دهند.

## F1 — فهرست و انتخاب واقعی از کمپین میزبان

- fixture ابتدا Frozen Forest واقعی، `serpulo:64`، را بارگذاری می‌کند و ذخیرهٔ واقعی میزبان را می‌سازد؛ سپس پیش از فعال‌سازی authority به Ground Zero واقعی، `serpulo:170`، می‌رود. از زمان فعال‌سازی و شروع مهمان، میزبان دیگر سکتور عوض نمی‌کند.
- هویت `campaign-fixture` از فایل میزبان خوانده می‌شود، نه از پیشنهاد مهمان. ID سکتورها از محتوای واقعی سیارهٔ میزبان و metadata ذخیره استخراج/تطبیق داده می‌شود؛ production هیچ ثابت 170 یا 64 ندارد.
- `host-active`، `owned-saved`، `owned-active`، `new-locked` و `new-unsaved` مجزا هستند. سکتور دارای save و core واجد پذیرش است؛ یک preset قفل‌شده بدون save به‌اشتباه قابل انتخاب نمی‌شود. این قرارداد محدود headless است، نه بازسازی کامل vanilla launch eligibility یا ساخت SaveSlot جعلی.
- درخواست LIST واقعاً از موتور مهمان می‌آید؛ Python همان bytes را به میزبان می‌رساند. میزبان فهرست را تولید می‌کند. SELECT و GRANT نیز همین مسیر را دارند؛ runner پاسخ موفق جعل نمی‌کند.
- session، campaign، شکل پیام، نوع/نسخه/پروتکل، ID، طول UTF-8 و سکتور بررسی می‌شوند. انتخاب Ground Zero، سکتور ناشناخته و سکتور جدید قفل‌شده رد می‌شوند.
- دو درخواست متعارض بدون انتظار پاسخ پشت سر هم صف شدند و هر دو `reason=owned` گرفتند. این تست رقابت admission صف‌شده در همان thread authority است، نه ادعای دو اتصال شبکه‌ای یا آزمون دو مهمان هم‌زمان.
- replay همان selection ID عین GRANT قبلی را برمی‌گرداند؛ تغییر سکتور با همان ID conflict است. تاریخچهٔ موفق در RAM سقف ۲۵۶ دارد. `.lease` با CREATE_NEW قبل از نمایش grant ثبت می‌شود؛ stale lease پس از restart میزبان fail-closed است، نه بازیابی خودکار.

## جلوگیری از دو writer

این lock متعلق به کد M4 است، نه یک «قفل بارگذاری vanilla» فرضی:

1. host یک قفل coordinator برای کل کمپین می‌گیرد.
2. تصمیم‌های admission روی thread موتور میزبان سریالی‌اند؛ یک sector فقط یک lease/grant دارد.
3. owner پیش از SaveIO.load یک OS FileLock اختصاصی سکتور را می‌گیرد و تا پایان عمر فرایند نگه می‌دارد، نه فقط هنگام save. replay grant هم از این قفل عبور نمی‌کند.
4. فایل lock inode پایدار است؛ حذف یا جایگزین نمی‌شود. probe مستقل از JVM میزبان در زمان زنده بودن مهمان نتوانست قفل `serpulo-64.lock` را بگیرد و `SC_FIXTURE_LOCK_BLOCKED` چاپ کرد.
5. runner مهمان مالک را terminate و wait می‌کند، سپس جایگزین را می‌سازد؛ میزبان زنده می‌ماند. حداکثر دو JVM موتور زنده در نمونه‌های مانیتور ثبت شد.

کوچ/lease expiry، آزادسازی برای مالک دیگر، reconnect خودکار و بازیابی crash میزبان پیاده نشده‌اند. یک grant می‌تواند پس از پایان owner قبلی در worker جایگزین باز شود، ولی همان ID در میزبان زنده replay می‌شود. console و مشارکت‌کنندگان فایل‌سیستم باید قابل‌اعتماد باشند؛ فرایندی که عمداً API/lock را دور بزند تحت این ضمانت نیست. **اتصال co-op واقعی به همان سکتور تحویل نشده است.**

## F2 — ذخیره و بارگذاری واقعی، با موجودی مستقل

`SectorStore` از `SaveIO.write(Fi, SaveOptions)` استفاده می‌کند، فایل موقت یکتای هم‌پوشه را اعتبارسنجی و force می‌کند و با `ATOMIC_MOVE + REPLACE_EXISTING` جایگزین می‌کند. fallback غیراتمی ندارد. نام فایل از campaign و sector معتبر ساخته می‌شود و path مهمان پذیرفته نمی‌شود. symlink در اجزای مسیر رد می‌شود. مقصد normal user save یا Vars.saveDirectory نیست.

هر `.msav` شامل bytes واقعی جهان و metadata همان سکتور (`sc-sector-info`) و campaign (`sc-campaign`) است. پس از بارگذاری، موجودی core با query مستقیم fixture خوانده می‌شود، نه مقدار JSON coordinator. SectorInfo همان سکتور قبل از SaveIO.load بازگردانده می‌شود چون callback vanilla از آن برای مقداردهی core استفاده می‌کند. این بازگردانی save همان جهان است، **نه کپی موجودی جهان میزبان به مهمان**.

fixture به‌تنهایی مقدارها را seed می‌کند:

| جهان | قبل | ذخیره | worker جایگزین |
|---|---:|---:|---:|
| Ground Zero 170، میزبان | 100 | 100 | همان میزبان زنده، 100 |
| Frozen Forest 64، مهمان | 37 | 53، seed آزمایشی محلی | 53 از save واقعی |

دو save جدا نوشته شدند؛ save مهمان دوبار نوشته شد تا replacement فایل موجود نیز اجرا شود. hash فایل‌ها پس از load جایگزین تغییری نکرد. تست symlink فقط در workspace یک‌بارمصرف، مقصد را موقتاً به یک decoy داخلی اشاره داد؛ production با `reason=symlink` رد کرد و bytes decoy دست‌نخورده ماندند.

## TDD و شکست‌های واقعیِ حفظ‌شده

همهٔ نام‌ها زیر `build/` و با `-result.json` و لاگ‌های مربوط هستند؛ نتیجهٔ نام فایل جایگزین verdict نیست.

| اجرا | نتیجه و زمان |
|---|---|
| `m4-f1-red` | **شکست مورد انتظار، 14.246s**؛ baseline JAR واقعی fixture/save/Ground Zero را داشت ولی فرمان فعال‌سازی M4 غایب بود |
| `m4-f1-green` | **پاس، 17.785s**؛ دو موتور، directory/admission/replay و رقابت پذیرش |
| `m4-f2-red` | **شکست مورد انتظار، 37.447s**؛ F1 پاس، `sc-m4-open` در JAR admission-only غایب بود |
| `m4-f2-attempt1` | **شکست، 20.314s**؛ SaveIO.load اجرا شد ولی copper مهمان 0 به‌جای 37 شد |
| `m4-f2-green` | با وجود نام قدیمی **شکست، 16.749s**؛ حذف Logic.play به‌تنهایی کافی نبود، copper هنوز 0 بود |
| `m4-f2-green2` | **پاس، 25.636s**؛ حفظ SectorInfo همان سکتور مشکل را حل کرد، reload مقدار 53 را نگه داشت |
| `m4-acceptance` | **شکست harness، 24.252s**؛ تست منفی جدید با query میزبان grant را از cursor لاگ مصرف کرد؛ پاسخ واقعی وجود داشت ولی relay آن را پیدا نکرد |
| `m4-acceptance2` | **پاس نهایی، 26.451s**؛ همان JAR پذیرش قبلی، فقط ترتیب barrier تست اصلاح شد |

دو علت engine دقیقاً بررسی شدند: `Logic.play()` مسیر شروع بازی است و core را به loadout شروع پاک می‌کند؛ `Logic` در SaveLoadEvent کمپین، `SectorInfo.write()` را اجرا می‌کند که در worker تازه از info خالی موجودی صفر می‌سازد. مقدار موردانتظار 37 عوض نشد؛ بعد از load هیچ seed تصحیحی اجرا نشد. metadata همان save همراه bytes آن ذخیره شد. این بررسی هدفمند blocker بود، نه تکرار پژوهش کلی اقتصاد.

JARهای RED حفظ شدند: `build/m4-baseline.jar` و `build/m4-pre-f2.jar`. تست نهایی بعدها منفی‌های بیشتری گرفت؛ با JAR pre-F2 جدیدترین harness زودتر روی فرمان save/open غایب شکست می‌خورد. برای شواهد دقیق ترتیب اصلی، JSON و لاگ‌های RED محفوظ مرجع‌اند.

## پذیرش نهایی و artifact

`build/m4-acceptance2-result.json`: `passed=true`، **26.451 ثانیه**.

- PID میزبان **3440459**، مهمان **3440486**، جایگزین **3440559**؛ exit هر سه **143** پس از SIGTERM فرزند متعلق به آزمون، نه shutdown تمیز.
- tick میزبان **185.15570068359375 → 1273.9324840921909**؛ updateId **1 → 972**؛ sector=170 و copper=100 ثابت.
- worker جایگزین: copper=53، sector=64؛ tick **141.7371904477477 → 165.00346056744456** و updateId **5 → 28**.
- ۱۸ منفی ثبت‌شده در فهرست `negatives`، به‌علاوه دو رقابت owned و یک open پیش از تحویل پاسخ. هر منفیِ فهرست با query مستقیم میزبان barrier شد.
- کمترین available RAM نمونه‌ها **497.363 MiB**؛ بیشترین load5 **2.50049**. بیشترین RSS نمونه‌ها: host **151.125 MiB**، guest **114.629 MiB**، replacement **116.863 MiB**. heap با RSS متفاوت است؛ heap همه 128m بود.
- تست رگرسیون M3 **فقط یک بار**، `m4-m3-regression`: **passed، 22.044s**، دو سکتور واقعی. علت اجرا تغییر ثبت کلاس‌ها/build JAR بود؛ منطق M3 تغییر نکرد. M1 و M2 مجدداً به‌عنوان پیشرفت اجرا نشدند.

JAR نهایی `build/shared-campaign-pc.jar`، نسخه **0.0.4**:

```text
SHA-256: 0b6d61f96d2e887d871e82d7f46e7877e19dbb283bb925bd332de1e65ccf7973
Engine:  0dbd3275402ff6df8367620a47e0b24e4d4547332b99af7c434796520ca484c2
```

اعضای production بررسی شدند: SharedCampaignMod، StdioLedger، CampaignInventory، SectorSessions، SectorSessions$Checked، SectorSessions$LocalUniverse، SectorStore و mod.json. fixture/seed داخل JAR نیست. metadata و sc-status نسخهٔ جدید و محدودیت playable را توضیح می‌دهند.

saveهای پذیرش برای بررسی bytes نگه داشته شدند:

```text
build/m4-acceptance2-serpulo-64.msav    13083 bytes
8f80b28514ed77fb9fc634f19209af7ab53566709c4019b97e4e30e5ee2cffb8
build/m4-acceptance2-serpulo-170.msav   19049 bytes
b8ec126cd8e879fe05db02f354c21d33cfe35c0124cff6ae90913973f2d4d75b
```

## بازتولید و مرز ایمنی

فقط در ریشهٔ همین پروژه، ترتیبی:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/build.py
PYTHONDONTWRITEBYTECODE=1 python3 tests/m4_sector_session.py --label independent-m4
sha256sum build/shared-campaign-pc.jar
```

حد ۸۰ ثانیه از پیش از compile + حداکثر ۸ ثانیه cleanup برای دو فرزند زنده؛ boot ترتیبی؛ finally فقط فرزندان متعلق به آزمون را متوقف می‌کند. nice10، ActiveProcessorCount1، Xmx128m، javac96m، RAM≥200MiB و load5≤4 در مانیتور. این برنامه pytest نیست. artifactهای build gitignored هستند و parent باید در انتشار شواهد موردنیاز را مستقل بررسی/حفظ کند.

guard جداگانهٔ `SectorSessionLauncher` تنها delete/rename/write داخل workspace canonical یک‌بارمصرف را مجاز می‌کند؛ symlink و شبکه/exec رد می‌شوند. guardهای قدیمی دست‌نخورده ماندند. engine در `/opt/mindustry/server-release.jar` فقط خوانده شد. هیچ port/listener، دانلود، نصب، سرویس، Docker، GUI، personal save یا credential استفاده نشد. هشدار JLine/infocmp و SecurityManager در لاگ‌ها باقی‌اند.

یک تخطی محدود از دستور ابزار رخ داد: در ابتدای بررسی، فرمان فقط‌خواندنی `git status` اجرا شد، با اینکه کاربر git operations را منع کرده بود. در پیام همان زمان اعلام شد؛ بعد از آن git دیگری اجرا نشد و هیچ commit/push/reset یا تغییر git انجام نشد. تمام نوشتن‌های مأموریت داخل همین پروژه بودند.

## باقی‌مانده‌ها؛ بدون ادعای playable

- حمل‌ونقل فقط stdio محلی؛ API trusted console است، نه authentication یا remote protocol امن.
- saveها مخصوص workspace نشان‌گذاری‌شده و metadata M4 هستند؛ واردکردن خودکار save شخصی/vanilla پشتیبانی نمی‌شود.
- atomic replacement فقط فایل جهان همان سکتور است؛ research، settings عمومی و directory-fsync crash transaction نیست. ذخیرهٔ rollback چندفایلی/بازیابی crash بررسی نشده است.
- کنترل هم‌زمان بازیکنان یک سکتور، UI انتخاب، launch flow، remote active routing، vanilla research snapshot و transfer integration تحویل نشده‌اند.
- offline production فعلاً متوقف است؛ نباید این نسخه را کمپین معمولی طولانی‌مدت اجرا کرد.
- بررسی قفل coordinator دوم و load/save world-changed به‌صورت آزمون منفی مستقل اجرا نشده‌اند؛ code guard دارند ولی ادعای test coverage کامل نداریم.

بازبینی مستقل و انتشار بر عهدهٔ parent است.
