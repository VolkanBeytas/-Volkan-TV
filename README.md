# Volkan TV

Simfer Android TV 11 için uydu/TV Input Framework deneme uygulaması.

- IPTV kullanmaz.
- Android TV'nin TvInputManager, TvContract ve TvView altyapısını kullanır.
- OK tuşu kanal listesini açar.
- Kanal +/- ve yön tuşlarıyla kanal değiştirir.
- Sayı tuşlarıyla kanal numarasına geçmeyi dener.
- EPG bilgisini Android TV kanal veritabanından okumayı dener.
- Video yeniden kodlanmaz; TV input servisinden gelen görüntü doğrudan TvView ile gösterilir.

## Önemli
Bu ilk sürüm aynı zamanda tuner erişim testidir. Simfer üretici yazılımı tuner/kanal veritabanını sadece sistem uygulamalarına açıyorsa, normal APK tüm kanallara erişemeyebilir. Bu durumda uygulama hata/kanal sayısı 0 gösterebilir; sonraki adım üretici servislerini incelemektir.

## Android
- Minimum: Android 11 / API 30
- Target: API 30
- Compile SDK: API 34

## APK derleme
GitHub Actions otomatik olarak `app-debug.apk` üretir.
