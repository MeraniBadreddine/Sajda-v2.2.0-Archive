# Sajda v2.5.7 PRO — تقرير ختامي

## بيئة البناء هنا
لا يوجد Android SDK/Gradle شبكة في بيئة الوكيل. يجب تشغيل محلياً:
`./gradlew :app:testDebugUnitTest :app:assembleDebug`

## البنود

### P0-1 سياسة الموقع (الخيار أ) — **تم**
- حذف `LocationCandidatePolicy` و `ProviderSettlePolicy` واختباراتهما.
- السياسة: أول fix صالح يُعتمد فوراً ويتوقف البحث (تعليق رأس LocationController).
- حذف bestCurrentFix/bestFixRunnable؛ `commitIfUsable` → `deliverFix`.
- LocationController: **358→~350** سطر.
- اختبار: `FirstUsableFixPolicyTest.firstUsableFixCommitsAndStops`.

### P0-2 صلاحيات — **تم**
- `onPermissionLapsed` لا يعمل إلا من `GRANTED`.
- `DENIED_PERMANENT` لاصق حتى `onPermissionGranted`.
- `PermissionLapseTest` يغطي المسارين.

### P1-1 AtKaaba — **تم**
- `DisplayPolicy.shouldShowAtKaaba(distance + accuracy ≤ 100)`.
- MainActivity يستدعيها؛ `AtKaabaDisplayTest` على السياسة المشتركة.
- `QiblaEngine.solve` @Deprecated.

### P1-2 Last known — **تم**
- `lastKnownScore = (accuracy?:5000) + ageMinutes`.
- اختبارات accurate-old vs vague-fresh.

### P2-1 بنية — **جزئي**
- `LocationPrompter` مستخرج (حوارات).
- `SessionRenderer` **لم يُستخرج** بالكامل (كان سيتجاوز النطاق الزمني وخطر كسر).
- MainActivity: **855 سطر** (الهدف ≤550 **لم يُحقق**).

### P2-2 CompassView صغير — **تم**
- حلقة + نص `compass_too_small` عند r < 48dp.

### P2-3 a11y — **تم/مؤكد**
1. layout-land contentDescription موجود.
2. AccessibilityAnnouncer يستخدم `HeadingPolicy.isAligned`.
3. إعلان atKaaba عند الانتقال (مفتاح memo).
4. applyPoint يهيئ `Confidence.LOW`.

### P3 Instrumented — **جزئي**
- اختبار smoke مع GrantPermissionRule؛ TODO صريح لتغطية city→locating كاملة.

## أحجام
| ملف | سطور | هدف |
|-----|------|-----|
| LocationController | ~350 | ≤350 |
| MainActivity | 855 | ≤550 ❌ |

## يدوي (على جهازك)
أ) مدينة → tap → رسالة فشل صريحة
ب) مكة يدوي → درجة + موقع تقريبي
ج) إطفاء موقع → كبسولة تتغير
د) دوران شاشة أثناء البحث
