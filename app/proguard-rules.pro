# [P9-9b-R8] قواعد الاحتفاظ المحافظة لتصغير R8 — كل قاعدة موثقة بسببها.
# مبدأ الموجة: أقل عدد ممكن من القواعد — قواعد AGP/KGP الاستهلاكية تغطي Compose وRoom وCoroutines
# تلقائياً (consumer rules داخل المكتبات نفسها). ما يلي فقط أسطح قد تكسرها إعادة التسمية:

# ── 1) كيانات Room وجداولها — أسماء الأعمدة/الجداول مبنية من أسماء الحقول عبر KSP،
#      والنسخ الاحتياطي (BackupRepo) يبني الكيانات بترتيب معاملات المُنشئ — احتفاظ صريح للأمان
-keep class com.superbiz.app.data.db.** { *; }

# ── 2) مُصدّرات XLSX/CSV — ExportSheet وXlsxSheets تعتمد على أسماء دوال عمومية مستقرة
-keep class com.superbiz.app.export.** { *; }

# ── 3) ZXing (باركود المنتجات + QR ZATCA من P9-9a) — مكتبة مستقرة لكن قواعدها الاستهلاكية
#      غير شاملة لzxing-android-embedded في كل الإصدارات — احتفاظ محافظ
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.** { *; }

# ── 4) الحفاظ على أسماء خطوط Source قياسية لتقارير الأعطال القابلة للقراءة
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
