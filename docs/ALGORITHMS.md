# دليل خوارزميات SuperBiz — العقود الحسابية الموثقة

> **v5.2.0 (R12) — لقطة العقود حتى R12.** يوثق هذا الملف خوارزميات `domain/algo/` حتى موجة R12 بعقد مدخلاتها
> ونتائجها وسلوكها مع الفراغ والانحراف. أضيفت بعده موجات R13/R14/R15 (`R13Smart/R14Smart/R15Smart`) بنفس
> نمط العقود موثقة في KDoc ملفاتها. تحديثات عقود فردية بموجة P6 (M6-19..M6-24: σ خطر النفاد، جريبس بσ
> العينة، الوسيط المرجّح، حروق الأصفار، الانحيازات) موثقة في الكود وتقرير P6. كل الدوال نقية (بلا Android) وحتمية،
> ومغطاة باختبارات وحدوية على أمثلة محسوبة يدوياً (تم التحقق الميداني JVM لموجتي
> R11 وR12 كاملتين — 91 فحصاً سلوكياً لموجة R12 عبر scripts/r12-harness).

**عقد الصدق العام:** مدخلات فارغة أو منحلة → نتيجة `null` أو حيادية موثقة
(لا أرقام مزيّفة)، بلا قسمة على صفر، وبلا مؤثرات جانبية. الواجهة تخفي البطاقة
كلياً عندما تَعُد الخوارزمية "لا بيانات كافية".

---

## R12Smart.kt — موجة R12 (20 خوارزمية، 15 كائناً)

### AnomalyMath
- `ewmaBands(series, λ=0.3, k=3.0) → EwmaBands?` — حدود EWMA بخط أساس النصف الأول (الطور الأول)؛
  يُراقب النصف الثاني ويُبلّغ أول فهرس خرق. خط أساس بلا تشتت ⇒ أي انحراف فعلي خرق (عتبة 1e-9).
  null إذا < 8 نقاط أو قيمة غير منتهية. الحكم STABLE/SHIFT.
- `cusumShift(series, threshold=5.0) → Cusum?` — مجموع انحرافات معيارية متراكم بجانبي عتبة k=0.5؛
  الحكم RISING/FALLING/STABLE. الأقلية في السلسلة تتراكم أسرع (حتمية موثقة). null إذا < 6 نقاط أو σ=0 (يُرجع STABLE).

### CompareMath
- `periodCompare(current, previous?, yearAgo?) → PeriodCompare?` — MoM/YoY بأساس |base|؛
  base=0 أو null ⇒ نسبة null لا اختلاق. الحكم UP/DOWN/FLAT/NEW. current غير منتهٍ ⇒ null.

### PaymentMath
- `mix(cash, check, other) → Mix?` — حصص مئوية وverdict: CHECK_HEAVY (>40٪ شيكات) / CASH_ONLY (≥99.5٪) / BALANCED.
- `timing(daysRelDue: List<Int>) → Timing?` — مبكر/في اليوم/متأخر؛ SLIPPING إذا تأخر > 30٪، PROMPT إذا مبكر ≥ 40٪.

### TurnMath
- `turnover(cogs, avgStock, periodDays=90) → Turnover?` — دوران مُسنَد (365/الفترة) وأيام بقاء؛ FAST ≥ 6 / OK ≥ 3 / SLOW.
- `sellThrough(received, sold) → SellThrough?` — نسبة البيع من الوارد مقصوصة 0..100؛ HOT ≥ 70٪ / NORMAL ≥ 30٪ / STALE.

### QuartileMath
- `classify(named: List<Pair<String,Double>>) → List<QRow>?` — أرباع باستيفاء خطي؛ 1 = الربع الأعلى.
  ترتيب تنازلي. null إذا < 4 عناصر أو قيمة غير منتهية.

### HourMath
- `activeSpan(hourTotals: DoubleArray(24), floorPct=20.0) → ActiveSpan?` — أطول امتداد متصل ≥ 20٪ من الذروة؛
  FOCUSED ≤ 4 ساعات / NORMAL ≤ 8 / SPREAD. null إذا فارغ أو الحجم ≠ 24.

### VoidMath
- `voidTrend(weeks: List<Pair<Int,Int>>) → VoidReport?` — أسبوع (ملغاة، إجمالي)؛ صعود إذا تجاوزت نسبة الأخير
  متوسط ما قبله بـ 2 نقطة. ALARM ≥ 10٪ / WATCH ≥ 4٪ / OK. null إذا < أسبوعين صالحين.

### UpliftMath
- `priceUplift(p0,q0,p1,q1) → Uplift?` — ΔRevenue = أثر السعر Δp·q1 + أثر الكمية Δq·p0 + التقاطعي Δp·Δq؛
  الحكم مقابل 0.5٪ من الإيراد القديم: WIN/LOSS/NEUTRAL. يتطلب الأسعار والكميات الأربع > 0.

### CostMath
- `costCreep(unitCostByMonth) → Creep?` — انحدار خطي شهري؛ الميل من المتوسط: SPIKE > 8٪ / CREEPING > 2٪ /
  FALLING (ارتفاع كلي < −5٪) / STABLE. يتطلب ≥ 4 أشهر موجبة.

### PayoffMath
- `monthsToClear(totalOpen, avgMonthlyPay) → Payoff?` — ceil(المفتوح ÷ الدفعة)؛ SHORT ≤ 3 / MEDIUM ≤ 8 / LONG.

### DunningMath
- `stages(rows: List<Pair<Double,Int>>) → Dunning?` — (مفتوح، أيام تأخر) إلى خمس مراحل CURRENT/REMIND(1-15)/
  URGE(16-45)/FINAL(46-90)/COLLECT(>90) مع مرحلة الأثقل بعد CURRENT. null إذا فارغ أو المجموع ≤ 0.

### AuditMath
- `orphanPayments(payments, validInvoiceIds) → List<OrphanRow>` — دفعات تشير لفواتير غير موجودة.
- `overpaidInvoices(rows: (id,total,paid)) → List<Overpaid>` — paid > total + 0.01، مرتبة بالزيادة تنازلياً؛ غير المنتهي يُهمل.

### CatalogMath
- `neverSold(items: List<CatItem>, soldIds) → List<CatItem>` — ما لا يظهر في المبيعات إطلاقاً، بترتيب رأس المال الراكد.
- `categoryGaps(productsByCat, soldByCat) → List<CatGap>` — فئات بمنتجات وصفر مبيعات؛ الأسماء الفارغة تُهمل.

### CartMath
- `cartSizeTrend(weeks: List<Pair<Double,Int>>) → CartTrend?` — متوسط الأسطر لكل فاتورة أسبوعياً؛
  GROWING > +5٪ / SHRINKING < −5٪ / FLAT. يتطلب ≥ 4 أسابيع صالحة (بفواتير).

### ShareMath
- `concentrationDrift(now, before) → ShareDrift?` — HHI (مجموع مربعات الحصص) بين نصفين + نصيب الأكبر؛
  CONCENTRATING/DIVERSIFYING/STABLE بعتبة 0.01. فترة بلا إيراد ⇒ null.


## R11Smart.kt — موجة R11 (20 خوارزمية، 11 كائناً)

### TrendMath
- **linearTrend(values)**: ميل OLS على ترتيب النقاط + تقاطع + R². أقل من 3 نقاط → `null`؛ سلسلة ثابتة → r2=1.0 وSTABLE. الحكم: |ميل×(ن−1)| > 2% من المتوسط → RISING/FALLING وإلا STABLE.
- **momentumPct(series)**: نسبة تغير متوسط النصف الأحدث مقابل الأقدم (النصف الزائد يذهب للأحدث). أقل من نقطتين أو أساس ≤0 → `null`.

### CashMath
- **burnAndRunway(cash, inflow, outflow, windowDays=30)**: الحرق اليومي = (خارج−داخل)/النافذة؛ حرق ≤0 → صمود `null` وحكم SURPLUS؛ النقد ≤0 → صمود 0. الحكم: <30 CRITICAL، <60 TIGHT، وإلا OK (الحدود حصرية).
- **cashGapCurve(start, events)**: رصيد متحرك عبر أحداث مجمعة باليوم ومرتبة تصاعدياً؛ يعيد (أدنى رصيد ويومه، رصيد النهاية، عدد الأيام السالبة). قائمة فارغة → البداية و0.

### TaxMath
- **vatPosition(outputTax, inputTax, outputNet)**: net = مخرجات−مدخلات؛ >0.005 PAYABLE، <−0.005 REFUND، وإلا NEUTRAL؛ المعدل الفعلي = net÷صافي المخرجات (صافي ≤0 → `null`).
- **roundingDrift(rows)**: لكل صف (متوقع، فعلي) فرق مقرّب؛ يعيد (صافي الانحراف، عدد الصفوف المتجاوزة 0.005، فهرس الأسوأ، أسوأ فرق). فارغ → أصفار.

### PriceMath
- **marginAudit(items, floorPct)**: أسطر (اسم، سعر، تكلفة) — سعر ≤0 مستبعد؛ هامش < الأرضية يُعدّ؛ يعيد (العدّ، الصالح، الحصة٪، أسوأ 5 تصاعدياً بالهامش).
- **discountLeak(rows, minGross=100, topK=5)**: نسبة الخصم الكلية (إجمالي ≤0 → `null`) + أسوأ الأطراف (إجمالي ≥ minGross) تنازلياً بالنسبة ثم بالاسم.

### InvoiceMath
- **aovTrend(weeks)**: لكل أسبوع (مجموع، عدد) → aov حيث عدد >0؛ أقل من أسبوعين صالحين → `null`؛ الاتجاه من TrendMath.linearTrend والزخم من momentumPct (تركيب).
- **duplicateSuspects(rows, windowDays=7)**: نفس الطرف ومبلغ متطابق ضمن هاللة وفارق أيام ≤ النافذة؛ كل صف يُستهلك في اقتران واحد كحد أقصى (لا انفجار تربيعي)؛ أقرب 5 بفارق الأيام.

### CustomerMath
- **rfmSegment(recencyDays, frequency, monetary)**: جدول قرار موثق: بلا طلبات NO_ORDERS؛ ≤7يوم+تكرار≥5 CHAMPION؛ ≤30+≥3 LOYAL؛ ≤30 NEW؛ ≤60 PROMISING؛ ≤90 AT_RISK؛ ≤180 SLEEPING؛ وإلا LOST.
- **churnRisk(lastOrderDay, today, avgGapDays)**: صمت ≥ 1.5× الفجوة الاعتيادية (فجوة ≤0 → `null`، نسبة <1.5 → `null`)؛ الدرجة = min(100, نسبة×25)؛ ≥75 HIGH، ≥50 MED، وإلا WATCH.

### ExpenseMath
- **budgetVsActual(baseline, actual, tolerance=15%)**: اتحاد الفئات؛ أساس 0 مع فعلي >0 → فئة جديدة (over فوراً)؛ الترتيب بفارق فعلي−أساس تنازلياً ثم الاسم.
- **fixedVariableSplit(amountsMonths, threshold=3)**: فئة ثابتة إذا ظهرت في ≥ threshold أشهر مميزة؛ مجموع ≤0 → `null`؛ الحصص من المجموع، وقائمة الثابتة مرتبة بوحدات الكود.

### StockMath
- **reorderPlan(stock, avgDaily, leadDays, safety)**: الهدف = طلب المهلة + الأمان (السوالب تُعامل صفراً)؛ الكمية = max(0, ⌈الهدف−الجرد⌉)؛ الحكم: بلا هدف OK، جرد ≤0 OUT، ≤ أمان URGENT، < هدف SOON، وإلا OK.
- **gmroiByCategory(margin, stockValue, periodMonths)**: هامش الفترة ÷ قيمة المخزون ×(12÷periodMonths) سنوياً؛ مخزون ≤0 أو فئة فارغة مستبعدة؛ تنازلي بالمردود ثم الاسم.

### CurrencyMath
- **fxExposure(openByCurrency, rates, base)**: يستبعد العملة الأساسية وما لا سعر معتمد له (rate ≤0)؛ مجموع ≤0 → `null`؛ حصة أكبر عملة ≥50% CONCENTRATED وإلا DIVERSIFIED.

### CheckMath
- **bounceStats(rows, minIssued=3, topK=5)**: صفوف (طرف، صادر، مرتجع)؛ الأطراف تحت الحد مستبعدة؛ النسبة الكلية = مرتجعات÷صادر (صادر 0 → `null`)؛ الترتيب بالنسبة تنازلياً ثم الصادر ثم الاسم.

### InstallmentMath
- **delinquencyProfile(openRows, today)**: أقساط مفتوحة تجاوزت الاستحقاق (فرق 0 ليس متأخراً) بشرائح 1-15/16-30/31-60/60+؛ المعدل = متأخر÷كل مفتوح؛ بلا مفتوح → `null`.

### RhythmMath
- **todayPace(todayTotal, weekdayAvg, dayProgressPct)**: المتوقع حتى الآن = معدل يوم الأسبوع × نسبة التقدم؛ معدل ≤0 أو تقدم خارج (0..100] → `null`؛ نسبة الإيقاع ≥115 AHEAD، ≥85 ON_TREND، ≥50 SLOW، وإلا CRITICAL.

---

**عقد الصدق العام:** مدخلات فارغة أو منحلة → نتيجة `null` أو حيادية موثقة
(لا أرقام مزيّفة)، بلا قسمة على صفر، وبلا مؤثرات جانبية. الواجهة تخفي البطاقة
كلياً عندما تَعُد الخوارزمية "لا بيانات كافية".

---

## R10Smart.kt — موجة R10 (20 خوارزمية)

### PricingMath
- **zScoreOutliers(values, threshold=3.5)**: كشف شواذ بمقياس z منيع `0.6745·(x−وسيط)/MAD`. حجم <4 أو MAD=0 → قائمة فارغة (عقد صريح). تعيد `(فهرس، قيمة، z)`.
- **markdownLadder(currentPrice, unitCost, ageDays, steps=defaultLadder, floorAtCost=true)**: أول شريحة عمر يحققها العمر تُطبَّق كنسبة خصم؛ الأرضية عند التكلفة عندما floorAtCost. الافتراضي: 60→5%، 90→10%، 120→20%، 180→30%.

### SeasonMath
- **weekdayProfile(points)**: أيام ISO (الاثنين=1..الأحد=7)؛ مؤشر اليوم = متوسطه ÷ المتوسط الكلي ×100. بلا نقاط أو متوسط ≤0 → فارغ. مرتب تنازلياً بالمؤشر.
- **hotCells(matrix 7×24, topK=3)**: أفضل الخلايا (يوم، ساعة) بقيمة >0 مع حصة٪ من الأعلى؛ تعادل → أصغر يوم ثم أصغر ساعة (حتمية).

### MarginMath
- **portfolioMargin(revenue, cost)**: هامش مرجّح + HHI على حصص٪ (0..10000). الحكم: ≥2500 HIGH، ≥1500 MED، وإلا LOW. مجموع إيراد ≤0 → null.
- **paretoABC(revenue)**: تراكمي ≤80% A، ≤95% B، وإلا C. ترتيب تنازلي بالإيراد ثم الاسم.
- **capitalEfficiency(profit, capital)**: ربح لكل 100 وحدة من قيمة المخزون؛ رأس مال ≤0 يُستبعد.

### FlowMath
- **agingBuckets(open, today)**: شرائح 0-30/31-60/61-90/90+ من تاريخ الاستحقاق؛ غير المستحقة في 0-30؛ المفتوح ≤0 يُهمل.
- **collectionForecast(open, today, horizonDays=14, prob=[.95,.80,.60,.40])**: مجموع المفتوح المستحق خلال الأفق × احتمال شريحته.
- **dsoTrend(weekly)**: لكل أسبوع dso = مفتوح ÷ مبيعات ×7؛ أسبوع بلا مبيعات يُحذف؛ <2 نقطة → null؛ الاتجاه بإشارة ميل خطي (±0.01).

### BasketMath
- **marketBasketLift(baskets, minSupport=2, topK=5)**: لكل زوج support=|A∪B|/N، confidence=|A∪B|/|A|، lift=conf/(|B|/N)؛ يُستبعد ما دعمه أقل أو lift≤1.
- **nextProduct(baskets, productId, topK=3)**: ماركوف رتبة-1 على ترتيب السلة؛ التكرارات المتتالية لنفس المنتج لا تُحسب.

### RiskMath
- **healthScore(liquidity, marginTrend, debtRatio, expenseRatio)**: مكونات 0..100 موزونة (سيولة 35%: 2.0→100 خطي؛ هامش 25%: ±5% حول 50؛ مديونية 25%: 0→100 حتى 2.0→0؛ مصروفات 15%: 0→100 حتى 1.0→0). الحكم: ≥80 HEALTHY، ≥60 OK، ≥40 WATCH، وإلا RISK.
- **customerConcentration(revenue, topN=3)**: حصة أعلى topN + HHI بنفس عتبات الحكم. مجموع ≤0 → null.
- **maturityLadder(dueDays, today, weeks=8)**: تجميع أسبوعي؛ المتأخر (diff<0) في الأسبوع 1؛ خارج الأفق يُهمل.

### GoalMath
- **requiredPace(remaining, daysLeft, capacity)**: required=remaining/daysLeft؛ feasible فقط عندما daysLeft>0 وcapacity>0 وrequired≤capacity. الحكم: DONE/EXPIRED/ON_TRACK/NEEDS_BOOST.
- **milestoneProjection(done, target, pace)**: أيام حتى 25/50/75/100% بالتقريب لأعلى؛ المحطة المتجاوزة فعلياً → null؛ pace≤0 → كلها null.

### QualityMath
- **dupScore(a, b)**: 0..100 = 60% Jaro-Winkler المعياري + 40% تداخل الرموز بعد التطبيع العربي؛ طرف فارغ → 0.
- **outliersIQR(values, k=1.5)**: أسوار Q1−k·IQR / Q3+k·IQR؛ حجم <4 → null؛ IQR=0 → لا شواذ.
- **roundingSweep(amounts, denominations)**: جرد جشع موثّق (أمثل للفئات النقدية القياسية) + مجموع الكسور غير المغطاة؛ السالب يُهمل.

---

---

## R9Smart.kt — موجة R9 (21 خوارزمية)

### GrowthMath — النمو والتنبؤ
| # | الدالة | العقد |
|---|---|---|
| B1 | `holtForecast(series, α=0.4, β=0.2, horizon)` | فارغة→فارغة؛ نقطة واحدة→مسطح؛ الاتجاه الابتدائي = فرق أول نقطتين؛ القيم غير سالبة دائماً |
| B2 | `smaCross(series, short=7, long=21)` | +1 ذهبي / −1 موت / 0 لا إشارة؛ القرار على آخر نقطتين فقط؛ يتطلب long+2 نقاط |
| B3 | `forecastAccuracy(actual, predicted)` | يستبعد الفعلي≤0؛ MAPE + انحياز + درجة 1..4 (4 ممتاز <10%)؛ أحجام مختلفة→null |
| B4 | `seasonalityStrength(series, period=7)` | نسبة تباين متوسطات الفترات للكلي 0..1؛ يتطلب فترتين كاملتين؛ بلا تباين→0 |

### CashMath — النقد والسيولة
| # | الدالة | العقد |
|---|---|---|
| B5 | `billSweep(cash, bills)` | تعظيم عدد الفواتير المسددة كلياً؛ ≤12 فاتورة حل أمثل بـDP للمجموع الجزئي؛ أكثر→جشع من الأصغر؛ المبالغ غير الموجبة تُستبعد |
| B6 | `runwayUnderStress(cash, in, out)` | ثلاثة سيناريوهات (100%/70%/40%)؛ صافٍ موجب→9999 (آمن موثق)؛ الأيام = cash÷(−صافي شهري)×30 |
| B7 | `seasonalReserve(outflows, months=2)` | أعلى (p90 خطي، وسيط أفضل 3 أشهر) × أشهر؛ بلا تاريخ→0 |

### CreditMath + RetentionMath — العملاء
| # | الدالة | العقد |
|---|---|---|
| B8 | `behaviorScore(onTime, late, bounced, avgDelay)` | 100 − 45×نسبة التأخير − 20×(متوسط التأخير/60) − 10×كل ارتجاع (سقف 3)؛ محصورة 0..100 |
| B9 | `creditLimit(avgOrder, orders, score)` | متوسط الطلب × min(طلبات,8) × (0.5+0.5×الدرجة)؛ فئة HIGH ≥5×، LOW ≤1.5× |
| B17 | `cohortRetention(purchases, currentMonth)` | الفوج بشهر أول شراء؛ يعيد m1/m2/m3 للفوج الأحدث المؤهل؛ لا فوج→null |
| B18 | `npsProxy(repeat, oneTime, refunded)` | (متكررون−مرتجعون)÷الكل×100؛ محصور −100..100؛ بلا عملاء→0 |
| B19 | `nextBestAction(segment, churn, overdue)` | جدول قرار: COLLECT > WIN_BACK > NURTURE > UPSELL > OK — يعيد كوداً والواجهة تترجم |

### StockMath — المخزون
| # | الدالة | العقد |
|---|---|---|
| B10 | `safetyStock(avgDaily, σd, lead, σl, z=1.65)` | z×√(مهلة×σd²+d̄²×σl²) — يدمج تذبذب الطلب والمهلة؛ مدخلات سالبة→0 |
| B11 | `crostonIntermittent(demand)` | متوسط الحجم ÷ متوسط الفترة؛ لا طلب→تنبؤ 0؛ نسخة أساسية لا SBA (موثق) |
| B12 | `batchAging(batches, today, warn=30, risk=90)` | CRITICAL/RISK/OK بالعمر؛ رأس المال المعرض = غير OK؛ الأقدم أولاً |
| B13 | `categoryBalance(stock, sales, tol=8pp)` | مقارنة حصة المخزون بحصة المبيعات؛ فئة بلا مبيعات بمخزون→OVER؛ بلا إجماليات→فارغة |

### RevenueMath — الإيراد والتسعير
| # | الدالة | العقد |
|---|---|---|
| B14 | `optimalPrice(priceQty)` | تركيب طلب خطي q=a+b·p؛ p*=−a/2b؛ يشترط b<0 و≥4 نقاط وp* داخل ±40% من المدى المرصود |
| B15 | `bundlePrice(costs, prices, margin)` | التكلفة÷(1−هامش) بسقف 95% من مجموع المنفردة؛ إن فشل→null |
| B16 | `profitAtRisk(daily, confidence=0.95)` | منهج رتيب على أرباح يومية مرتبة؛ ≥10 نقاط؛ الناتج موجب=حجم الخسارة |

### OpsMath — العمليات
| # | الدالة | العقد |
|---|---|---|
| B20 | `staffingPlan(hourTotals, hours)` | توزيع نسبي على ساعات ≥5% من الذروة؛ يُرتب بالنزول؛ مدخلات غير صالحة→فارغة |
| B21 | `eisenhower(tasks)` | عاجل≤3 أيام × مهم؛ DO_FIRST>SCHEDULE>DELEGATE>ELIMINATE؛ داخل الفئة الأعسر ثم الأقل جهداً |

---

## R9Adapters.kt — المهايئات النقية

| الدالة | العقد |
|---|---|
| `paymentPairsToBehavior(pairs)` | (استحقاق، سداد)→(في الوقت، متأخر، متوسط أيام التأخير)؛ غير المسدد يُستبعد؛ السداد بنفس يوم الاستحقاق = في الوقت |
| `ewmaBacktest(series, α=0.35)` | تنبؤ أحادي الخطوة: لكل i≥1 التنبؤ=EWMA حتى i−1؛ <2 نقطة→null |
| `dailyDemandStats(dailyQty)` | (متوسط، σ) مع حساب أيام الصفر (لا استبعاد) — أساس B10 |
| `shiftsToSchedule(plan)` | حذف الصفري وترتيب زمني للعرض |

---

## R7Smart.kt — موجة R7 (23 خوارزمية) — ملخص
تنبؤ 7 أيام بموسمية يوم-الأسبوع · أيام النفاد واحتماله (توزيع طبيعي بتقريب Zelen-Severo) · الراكد بخط 30/60 يوماً · خطة إعادة طلب بـz للأمان · مرونة لوغ-لوغ وسقف خصم وتدرّج كميات ونقطة تعادل · LTV وخطر فقد وRFM وموعد شراء · أولوية تحصيل وفجوة نقدية ومخاطرة ارتجاع · قفزات مصروفات وأزواج مشتركة وذروة ساعات واستقرار أرباح ومحاكي هدف وتفكيك صحة.

**الاختبارات:** R7SmartTest (23) + R9SmartTest (22) + R9AdaptersTest (4) + R9FixesTest (6 انحدار للإصلاحات) + باقي الحزمة = **369 اختباراً أخضر**.

---

## R13Smart.kt — موجة R13 (20 خوارزمية في 15 كائناً)

عقد الصدق العام نفسه: بلا بيانات كافية أو بمدخلات غير منتهية ⇒ null/قائمة فارغة، ولا وقت نظام داخل أي دالة. الفهارس مبكّرة على الصفر.

| الدالة | العقد المختصر |
|---|---|
| `RobustMath.theilSen(series)` | وسيط ميول كل الأزواج؛ null تحت 4 نقاط؛ relSlopePct نسبة الميل لكل خطوة إلى متوسط \|y\|؛ حكم RISING/FALLING عند ±0.5٪/خطوة وإلا FLAT |
| `RobustMath.madOutliers(values, k=3.5)` | z المعدَّل 0.6745(x−med)/MAD؛ MAD=0 ⇒ null (لا معلومة تشتت)؛ null تحت 6 نقاط |
| `AllocMath.largestRemainder(total, weights)` | أرضية إلى قرش ثم توزيع البواقي بالأكبر كسراً (تعادل: الأصغر فهرسة)؛ المجموع يطابق total تماماً؛ أوزان سالبة/مجموع ≤0/total سالب ⇒ null (R13-B10) |
| `AllocMath.cashBreakdown(amount, denoms=SAR)` | جشع أكبر فئة أولاً؛ البقايا دون أصغر فئة في leftover بصدق؛ amount سالب ⇒ null |
| `CorrMath.pearson(xs, ys)` | r∈[−1,1]؛ <3 أزواج أو تشتت صفري أو عدم تطابق طول ⇒ null |
| `CorrMath.spearman(xs, ys)` | رتب بمتوسط التعادل ثم بيرسون على الرتب — يلتقط أي علاقة رتيبة |
| `CadenceMath.drySpells(daily, threshold=0.005)` | يوم جافّ ما كان ≤ العتبة؛ أطول سلسلة/الجاري/النسبة؛ قائمة فارغة ⇒ null |
| `CadenceMath.regularityCV(intervals)` | CV=σ/μ لفواصل الشراء؛ ≤0.33 منتظم، ≤0.8 عادي، وإلا متقلب؛ <3 فواصل أو فاصل ≤0 ⇒ null |
| `GiniMath.gini(values)` | [0..1] بترتيب تراكمي؛ قيمة سالبة ⇒ null؛ كل صفر ⇒ 0.0 |
| `PercentileMath.percentileRank(sample, x)` | (أقلّ + نصف المساوي)/n×100 (طريقة المنتصف) |
| `PercentileMath.percentiles(values, ps=[25,50,75,90])` | استيفاء خطي R-7؛ <2 قيمة أو p خارج [0,100] ⇒ null |
| `WorkingMath.cashConversionCycle(rec, inv, pay, dCrSales, dCogs, dPurch)` | CCC=DSO+DIO−DPO؛ مقام ≤0 ⇒ مكوّن null وCCC null وحكم UNKNOWN؛ ≤15 سريعة، ≤45 معتادة، وإلا ثقيلة |
| `LoanMath.impliedRate(principal, pmt, months)` | تنصيف معادلة القيمة الحالية (200 تكرار، دقة 1e-12)؛ إجمالي الأقساط < الأصل ⇒ null؛ التساوي ⇒ 0؛ السنوي الاسمي ≤5 متساهل/≤15 معتاد/≤40 ثقيل/إلا فاحش |
| `PartyMatchMath.phoneMatch(a, b)` | تطبيع (أرقام فقط، إسقاط 00/966، نواة 9)؛ EXACT/LIKELY/NO |
| `FxDriftMath.rateDrift(rates, current)` | وسيط تاريخي مقابل الحالي؛ <2٪ مستقر، <8٪ للمراقبة، وإلا منحرف؛ <3 أسعار أو ≤0 ⇒ null |
| `FloatMath.floatDays(floats)` | وسيط/p90/أقصى لأيام الطفو؛ ≤7 سريع، ≤21 عادي، وإلا بطيء؛ <3 أو سالب ⇒ null |
| `ExpenseMixMath.topRiser(before, after)` | أكبر زيادة حصة بالنقاط المئوية؛ مجموعتا نافذتين >0 وإلا null؛ ≥5 تحوّل، ≥2 تسلّق |
| `LossMakerMath.belowCost(lines)` | أسطر (سعر<تكلفة، كمية>0) مجمّعة بالمنتج بالخسارة (تكلفة−سعر)×كمية، تنازلياً؛ بلاها ⇒ قائمة فارغة |
| `HalfLifeMath.engagement(events, halfLife=90)` | score=Σ amount×0.5^(العمر/عمر النصف)؛ صفوف ≤0 أو عمر سالب تُهمَل؛ عمر نصف ≤0 ⇒ null؛ تنازلي |
| `DispersionMath.priceSpread(prices)` | أسعار موجبة مقربة للقرش؛ سعر واحد موحّد، ≤5٪ متقيد، ≤15٪ متباعد، وإلا فوضى؛ <2 ⇒ null |

**الاختبارات:** R13SmartTest (26) + R13FixesTest (3 انحدار: B1 تهريب LIKE، B9 Infinity مرفوض، B15 صافي المدفوعات) — والتحقق الميداني الخارجي 107 فحصاً على JVM خالص (kotlinc 2.0.21).

**ملاحظة صدق R13-B4/B13:** خوارزميتا `LoanMath.impliedRate` و`FxDriftMath.rateDrift` موثقتان ومختبرتان لكنهما بلا بطاقة واجهة: الأولى لا معنى لها بيانياً لأن القسط = المُجدَّد/الشهور بالبناء (المعدل الضمني صفر حتماً)، والثانية بلا بيانات لأن التطبيق لا يُنشئ فواتير بعملة غير الأساس أصلاً. استُبدلتا في الواجهة بخطّة قصّ المصروفات وجيني الذمم.

## R14Smart.kt — موجة R14 (20 خوارزمية في 20 كائناً)

عقد الصدق العام نفسه: بلا بيانات كافية أو بمدخلات غير منتهية ⇒ null/قائمة فارغة، ولا وقت نظام داخل أي دالة (ما يحتاج «اليوم» يُمرَّر صريحاً).

| الدالة | العقد المختصر |
|---|---|
| `WinsorMath.winsorizedMean(values, p=5.0)` | قصّ إلى مئيني p و100−p باستيفاء R-7 ثم متوسط؛ ≥6 قيم و0<p<50 وإلا null؛ effectPct أثر القصّ |
| `HurstMath.hurst(series)` | R/S على أحجام 8..n/2 ثم انحدار log-log؛ ≥16 نقطة وσ>0 وإلا null؛ <0.45 ارتدادي، ≤0.55 عشوائي، وإلا مثابر |
| `KsMath.ksStatistic(a, b)` | أكبر فرق تراكمي بين عينتين؛ الحرج 1.358·√((n1+n2)/n1n2)؛ D>الحرج ⇒ SHIFTED |
| `CoverageMath.obligationCoverage(available, expected, obligations)` | نسبة الموارد إلى المستحق؛ ≥1.2 SAFE، ≥1.0 TIGHT، وإلا DEFICIT مع shortfall |
| `CampaignMath.requiredUplift(marginPct, discountPct)` | d÷(m−d)×100؛ d≥m ⇒ NEVER (null)؛ ≤15 FEASIBLE، ≤40 HARD، وإلا STEEP |
| `MarginMixMath.marginBySettlement(cash, credit)` | متوسطا الهامش والفارق؛ <3 قيم في أي جانب ⇒ null؛ فرق <0.5 نقطة EQUAL |
| `DrawdownMath.maxDrawdown(series)` | أعمق هبوط في النقد التراكمي من قمة لقاع لاحق مع فهارسهما؛ لا قمة موجبة ⇒ null |
| `FairShareMath.applyPayment(open, payment)` | شلّال أقدم استحقاقاً أولاً بالهللات (Long) — لا كسور تقريب؛ unallocated وclosedCount صريحان |
| `RunsMath.runsTest(values)` | جولات Wald–Wolfowitz حول الوسيط (المساوي للوسيط يُستبعد)؛ كل مجموعة ≥5 وإلا null؛ ±1.96 |
| `EntropyMath.effectiveCategories(values)` | exp(إنتروبي شانون) للحصص الموجبة؛ <3 تركّز، <6 توازن، وإلا تنوّع |
| `AbcXyzMath.matrix(items)` | A/B/C تراكمياً (80/95٪ والعابر ينضم للأعلى) × X/Y/Z بـcv الكميات (0.5/1.0)؛ خلايا «AX»..«CZ» |
| `ShrinkageMath.shrinkage(moves, cogs)` | قيمة ADJUST السالبة بتكلفة الوحدة ÷ COGS الفترة؛ لا سالبة ⇒ null؛ cogs≤0 ⇒ pct=null |
| `CatalogHygieneMath.hygiene(products)` | 5 خصائص/منتج (باركود/فئة/تكلفة/سعر/جرد سالب)؛ الدرجة 100×(1−عيوب/5n)؛ أسوأ ثلاثة |
| `BandMath.marginBands(margins)` | أحزمة ثابتة: سلبي/هزيل<10/منخفض<20/مقبول<35/جيد<50/ممتاز≥50؛ belowThin تصاعدياً |
| `BordaMath.bordaRank(lists)` | عدّ بورا على قائمتين+ بمجموعة معرفات واحدة وبلا تكرار وإلا null؛ تعادل: الأصغر معرفاً |
| `MoverMath.rankMovers(prev, curr)` | رتب تنافسية (المتساوون يتشاركون الأعلى)؛ ≥4 مشتركة وإلا null؛ أفضل 3 صعوداً/هبوطاً |
| `BlendMath.errorWeightedForecast(series, horizon=4)` | Holt(α=.5,β=.25)+SMA(4) موزونان بعكس MAE المعاودة من النقطة 6؛ خطأ صفري ⇒ وزن 1؛ ≥12 نقطة |
| `StalenessMath.weightedAge(items)` | (مفتوح، عمر): وسطى ووسين مرجّحان بالمفتوح؛ <30 طازجة، ≤60 شيخوخة، وإلا هرمة |
| `GapMath.checkTermsGap(gaps)` | إصدار→استحقاق: وسطى/p90/أقصى؛ ≥3 فجوات غير سالبة وإلا null؛ ≤10 قصير، ≤30 متوسط |
| `FixedReserveMath.dailyFixedReserve(fixed, days, cashIn)` | fixed/days مقابل متوسط التحصيل؛ ≥1.2 SAFE، ≥1.0 TIGHT، وإلا STRAINED؛ fixed≤0 ⇒ null |

**الاختبارات:** R14SmartTest (20) + R14FixesTest (4 انحدار: F2 أرشفة الاستيراد، F3 تسوية مبكرة متوازنة بالقروش، F15 دمج عبر الأدوار، F19 ضريبة محجوزة) — والتحقق الميداني الخارجي 134 فحصاً على JVM خالص (kotlinc 2.0.21، scripts/r14-harness).

**ملاحظة صدق R14:** كل الخوارزميات العشرين مربوطة ببطاقات واجهة على بيانات يُنتجها التطبيق فعلاً — لا ميزة ميتة (درس R13-B4/B13): التأجيل الأفقي للشيكات من حقلي issueDate/dueDate المحفوظين، والتسرّب من حركات ADJUST السالبة التي يسجلها المستخدم بنفسه، والهوامش من costTotal/تكلفة المنتج الحالية (الملاحظة الزمنية موثقة في البطاقات).


## R15 — عقود الخوارزميات العشرين (R15Smart.kt)

| الكائن | العقد | الصدق |
|---|---|---|
| `BayesMath.posterior(p, LR)` | p·LR/(p·LR+1−p) بلوغاريتم الأرجحيات؛ سلسلة الأدلة `posteriorChain` | null إذا p∉(0,1) أو LR≤0 |
| `BenfordMath.firstDigitShares(amounts)` | عدّادات الرقم 1..9 مقابل log10(1+1/d) + نصف مسافة التباين | null إذا <5 مبالغ موجبة |
| `CusumMath.detect(series, thr)` | S+ = max(0, S+ + z − 0.5)، إنذار عند S+ > thr | null إذا n<8 أو σ=0 |
| `ElasticityMath.arc(p1,q1,p2,q2)` | مرونة منتصف النقطة Δq%/Δp% | null إذا سعر ≤0 أو تساوي |
| `FourierMath.dominantCycle(v)` | ذروة مداوي |Σx·e^{−2πit/p}|×2/n لكل p∈2..n/2 | null إذا n<16 أو σ=0 |
| `GamblerMath.ruinHorizon(cash, μ, σ)` | أيام الاحتراق ⌈cash/−μ⌉ + العازلة cash/σ | null إذا μ≥0 أو cash≤0 |
| `GrubbsMath.extreme(series)` | G=max|x−μ|/σ مقابل جدول حرج 5٪ لـn=3..30 | null إذا n<3 أو σ=0 |
| `IqrMath.fences(series)` | Q1/Q3 خطي، سياج ±1.5×IQR، قائمة الشواذ بفهرسها | null إذا فارغة |
| `JaccardMath` | similarity=|∩|/|∪|؛ coPurchase بحدّ ظهور أدنى | 1.0 لفارغتين معاً |
| `KappaMath.agreement(a,b)` | (po−pe)/(1−pe) بحكم STRONG/MODERATE/WEAK | null إذا طولان مختلفان أو pe=1 |
| `LogitMath.probability(b0,b1,x1,b2,x2)` | σ(z) بتثبيت z∈[−40,40] | null عند أي مدخل غير منتهٍ |
| `MarkovMath.transitions(ups)` | مصفوفة 2×2 + πU=p(D→U)/(pDU+pUD) | null إذا انتقالات <2 أو المقام صفر |
| `NelsonMath.violations(series)` | قواعد 1 (3σ)، 2 (جولة 9)، 3 (اتجاه 6) بفواصل النهاية | null إذا n<9 أو σ=0 |
| `OeeMath.composite(f,s,m)` | f×s×m لكل مكوّن ∈[0,1] | null خارج المدى |
| `ParetoMath.cut(values)` | تراكم تنازلي حتى ≥80٪: (headCount, headShare) | null إذا الإجمالي ≤0 |
| `RecencyMath.rfm(...)` | نقاط 1..5 بحدود صريحة؛ الأقدمية معكوسة؛ مركّب 0.2/0.3/0.5 | null عند مدخل سالب |
| `TheilMath.fit(y)` | وسيط ميول الأزواج + وسيط البواقي | null إذا n<2 |
| `UtestMath.mannWhitney(a,b)` | U الأصغر + z بتصحيح التعادل؛ حكم |z|≥1.96 | null إذا عينة فارغة أو σ=0 |
| `VaRMath.historical(net, level)` | مئين قريب-الرتبة ceil((1−L)n)−1 بحارس 1e-9 + CVaR ذيل | null إذا n<10 أو L∉(0.5,1) |
| `ZipfMath.headShare(values)` | حصة أعلى ⌈20٪⌉ مقابل Σ1..k(1/i)/H_n | null إذا الإجمالي ≤0 |

## R16Smart.kt — موجة R16 «المنسّق الذكي» (20 خوارزمية في 5 كائنات — الأفق الثالث)

الفرق الجوهري عن الموجات السابقة: R16 لا يكتفي بوصف الماضي بل يتنبأ بالمستقبل ويعرض أصوله — طبقة تنبؤ فوق طبقة الإحصاء المثبتة، والفراغ صادق دائماً (عقد الصدق العام نفسه: بلا بيانات ⇒ null/قائمة فارغة، ولا يُقرأ وقت النظام داخل أي خوارزمية).

### CashFlow90Math — تنبؤ التدفق النقدي 90 يوماً (H3-1)

| الدالة | العقد المختصر | الصدق |
|---|---|---|
| `dailyNetStats(inflows, outflows, today, windowDays=56)` | شبكة يومية كاملة للنافذة؛ الأصفار جزء من الحقيقة؛ الصافي = وارد−صادر | null إذا نافذة<7 أو أيام نشطة<3؛ المبالغ السالبة/غير المنتهية تُهمَل صامتاً |
| `forecast90(cashNow, stats, scheduled, today, horizonDays=90, zPct=1.28)` | مسار أسبوعي: رصيد = نقد + د̄·الأيام + أحداث مجدولة؛ النصف‑عرض = z·σ·√الأيام المنقضية (مشي براوني — اتساع جذري)؛ حكم HEALTHY/TIGHT/DRY على lower النهائي مقابل 0 ونصف النقد | null إذا أفق<7 أو cashNow/σ غير منتهية؛ الأحداث خارج الأفق أو الماضية تُهمَل؛ أفق مقيد بـ90 |
| `dryDay(path)` | أول أسبوع lower80 < 0 — إنذار الجفاف قبل وقوعه | null إن لم يكن في المسار خطر ضمن الثقة 80% — لا رفع كاذب |
| `monthAhead(path)` | نهاية 30 يوماً المتوقعة + حدودها + صافي التغير | null إذا أسابيع المسار <4 |

### ReorderPointMath — نقاط إعادة الطلب الذكية (H3-2)

| الدالة | العقد المختصر | الصدق |
|---|---|---|
| `leadStats(observedLeadDays)` | متوسط/σ زمن التوريد الملحوظ (فجوات إعادة الشراء بالأيام) | null إذا n<2؛ OBSERVED إذا n≥5 وإلا SMALL (المستدعي يستبدل 7.0 ويعلن) |
| `reorderPoint(avgDaily, demandSd, leadMean, leadSd, serviceZ=1.65)` | التباين المركب: ROP = د̄·ل̄ + z·√(ل̄·σd² + د̄²·σل²) — تقلب التوريد يرفع النقطة ولو هادأ الطلب | null إذا د̄≤0 أو ل̄≤0 أو z≤0 أو مدخلات سالبة/غير منتهية |
| `daysOfSafety(stockQty, rop, avgDaily)` | (المخزون − النقطة)/د̄ — أيام قبل بلوغ النقطة | null إذا د̄≤0؛ السالب مشروع (تجاوز النقطة — إنذار) |
| `backtest(stockStart, dailySales, rop)` | محاكاة استنزاف بلا تعويض: يوم الإنذار/النفاد/الأيام الممنوحة (zero−reorder+1) وحكمها | null إذا مخزون≤0 أو مبيعات سالبة؛ لا نفاد ⇒ NO_RUNOUT (لا حادثة لقياسها)؛ EARLY≥3، ONTIME 1..2، وإلا LATE |

### EwmaAlertMath — تنبيهات استباقية بلا إزعاج (H3-3)

| الدالة | العقد المختصر | الصدق |
|---|---|---|
| `evaluate(series, lambda=0.3, kSigma=2.5)` | EWMA ثم dev = \|آخر نقطة − المستوى\|/σ أخطاء التنبؤ؛ الحِدّ: ≥kSigma+0.5 حرج، ≥kSigma تحذير، ≥0.6·kSigma مراقبة | null إذا n<10 أو σ≤1e-9 أو σ<2% من المستوى (أرضية القدرة — منع الإنذار الكاذب البنيوي على تيارات شبه ساكنة) |
| `hysteresis(previous, dev, kUp=2.5, kDown=1.5)` | حالة متصدر: الدخول عند kUp والخروج فقط تحت kDown — لا رفرفة عند العتبة | null إذا dev<0 أو غير منتهية أو kUp≤kDown أو حالة دخل غير معلنة |
| `shouldNotify(severity, lastNotifiedAt, nowMs, cooldownHours=24, quietFrom=22, quietTo=7)` | إعلان فقط عند حِدّ≥2 + انقضاء التهدئة + خارج سكون 22→07 | الطوابع صريحة؛ طابع مستقبلي معطوب ⇒ لا إعلان؛ أول تنبيه مسموح عند استيفاء الحِدّ |
| `digest(items, max=3)` | يفلتر الحِدّ≥2 ويرتب حِدّاً ثم dev تنازلياً ثم المفتاب أبجدياً ويقص | max<1 ⇒ فارغ؛ الصمت الكلي ⇒ بطاقة تختفي |

### QueryParseMath — استعلام عربي/إنجليزي محدود الدلالة (H3-4)

| الدالة | العقد المختصر | الصدق |
|---|---|---|
| `normalize(q)` | تشكيل/تطويل/همزات/ة→ه/أرقام هندية→لاتينية/إسقاط ترقيم + طبّع عربي موحد | حتمي كامل على العربي والإنجليزي |
| `parseRange(normalized)` | أطول تطابق يفوز («الشهر الماضي» قبل «الشهر»)؛ «آخر N يوم» برقمه الصريح حصراً — لا تخطف «المتأخرة» زوراً | لا مفتاح ⇒ null؛ بلا رقم صالح ⇒ n=0 (يُحل إلى الشهر الحالي) |
| `parseSubject(normalized, productNames, partyNames)` | أطول اسم نصُّه المطبَّع داخل الاستعلام — مطابقة حرفية بعد التطبيع فقط | لا سحر ضبابي — بلا تطابق ⇒ null |
| `parse(q, productNames, partyNames)` | نية + فترة + موضوع؛ قاعدة معلنة: صنف بلا نية ⇒ مخزون، طرف بلا نية ⇒ ذمم | لا نية ولا موضوع ⇒ null (خارج النطاق — فشل مهذّب) |
| `resolveRange(parsed, today)` | حل الفترة إلى (من، إلى) بطوابع صريحة؛ الأسبوع يبدأ السبت (KSA_WEEKEND) | الافتراضي الشهر الحالي؛ «آخر N» خارج 1..365 ⇒ الشهر الحالي |

**بوابة القبول المعتمدة:** مجموعة مرجعية عربية/إنجليزية (20 استعلاماً داخل النطاق + 3 خارجَه) في R16SmartTest تُرحَّح جاباً بنسبة 100%.

### NarrativeMath — روايات KPI بأصول معلنة (H3-5)

| الدالة | العقد المختصر | الصدق |
|---|---|---|
| `facts(...)` | بناء وقائع المقارنة الشهرية | null عند غير منتهٍ أو مبيعات/مصروفات سالبة؛ هدف سالب يُهمَل |
| `narrate(f)` | روايات بترتيب حتمي (مبيعات، هامش، حصة مصروفات، ذمم، هدف) وكل سطر يحمل أرقامه وأساسه الخام | لا نسبة على صفر — سطر بلا أساس يُسقَط كلياً (لا «صفر» مزيّف) |
| `provenance(f)` | سطر الإسناد الكامل بكل المدخلات الخام (4 أسطر) | — |

**الاختبارات:** R16SmartTest (30 — كلها محسوبة يدوياً: المسارات، σ اليدوي، المجموعة المرجعية، عقود الفراغ) + SmartCoordinatorVMTest (6 — Robolectric على Room حقيقية بنمط جهاز P37) — الموجة موصولة بالكامل: بطاقات الرئيسية (r16) + شاشة الدردشة (smart) + روايات لوحة المؤشرات، ولا خوارزمية بلا بطاقة (درس R13-B4/B13).
