# Sajda v2.5.1 — strict P0 close-out

## 1 LocationCandidatePolicy
تم. الترتيب: (أ) أدق بـ >25م يفوز دائماً (ب) أحدث >60ث ودقة ≤2× (ج) ضمن 25م → الأحدث (د) ترتيب المزود.
اختبار newerModeratelyWorse… أصبح 20م (assertTrue) + حالة 40م (assertFalse).

## 2 AtKaaba من المدينة اليدوية
تم. الشرط: `s.atKaaba && locationTrust == GOOD`. مكة اليدوية provisional → UNKNOWN → درجة عادية + موقع تقريبي.
اختبار AtKaabaDisplayTest.

## 3 عرض الكعبة الحقيقي
تم. `showQiblaMarker=false`، alignment=0، uncertain=true. إعلان a11y مرة عند الدخول. لا سهم قبلة.

## 4 نافذة التجميع
تم. ProviderSettlePolicy: GPS 5s / غير GPS 15s / تحسّن دقة >25م فوري. اختبارات وحدة بلا System clock.

## 5 applyDim على الألوان الديناميكية
تم. dimArgb() على arc/align/hub/bead/lubber.

## 6 LocationFlowState
تم جزئياً. الحقول private set؛ الانتقال عبر الدوال فقط. MainActivity ما زالت ~837 سطر (الهدف ≤700 لم يُحقق دون استخراج حوارات خارج النطاق).

## 7 اختبارات التدفق
جزئي. LocationFlowInstrumentedTest يعلن صراحةً TODO: لا تغطية كاملة لـ city→searching_gps على CI؛ الوحدة تغطي LocationFlowState.

## قبول البيئة
Gradle/SDK غير متوفرين هنا — يجب تشغيل:
`./gradlew :app:testDebugUnitTest :app:assembleDebug`
على الجهاز المحلي.
