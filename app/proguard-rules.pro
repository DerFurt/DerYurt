# DerYurt R8 kuralları
# OkHttp kendi consumer kurallarını getirir. WorkManager da öyle.
# Worker sınıfları reflection ile oluşturulur:
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
