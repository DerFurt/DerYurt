# DerYurt

KYK yurtlarındaki GSB wifi'sine (wifi.gsb.gov.tr) bağlanınca **otomatik giriş yapan** Android uygulaması. Yavaş açılan, bazen hiç açılmayan portal sayfasıyla uğraşmazsın.

> DerYurt bağımsız bir uygulamadır. Gençlik ve Spor Bakanlığı ile resmi bir bağlantısı yoktur.

## Nasıl çalışır
1. Wifi'ye bağlanınca Android uygulamayı uyandırır (`registerNetworkCallback` + `PendingIntent`; sürekli çalışan servis yok).
2. İstekler **wifi ağına bağlı** bir HTTP istemcisiyle gider (mobil veri açık olsa bile).
3. `GET https://wifi.gsb.gov.tr/login.html` ile oturum çerezi alınır, `POST /j_spring_security_check` ile giriş yapılır.
4. Başarı, `generate_204` ile gerçekten internete çıkılabildiğine bakılarak anlaşılır.
5. Oturum düşerse 15 dakikada bir kontrol (isteğe bağlı: "Oturumu sürekli izle" ile anında).

## Gizlilik ve güvenlik
- TC kimlik numaran ve şifren **sadece telefonunda**, Android Keystore anahtarıyla (AES-256-GCM) şifreli saklanır. Yedeğe girmez.
- Bilgiler **sadece sertifikası doğrulanmış `wifi.gsb.gov.tr`**'ye gönderilir. Başka bir ağdaysan hiçbir yere gönderilmez.
- Sunucu yok, analitik yok, reklam yok, konum izni yok. Loglarda kimlik bilgisi yok (bunu bir unit test de kontrol eder).
- Yanlış şifrede en fazla 2 deneme yapılır, sonra durur. Captcha çıkarsa geçmeye çalışmaz, sana haber verir.

## Derleme
```
./gradlew testDebugUnitTest assembleDebug
```
Release imzası `local.properties`'teki `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` satırlarından okunur (repoya girmez).

## Lisans
MIT. Bkz. [LICENSE](LICENSE).

---

**EN:** DerYurt automatically logs you in to the GSB dorm wifi captive portal in Turkey. Credentials are stored encrypted on-device (Android Keystore) and only ever sent to `wifi.gsb.gov.tr` over verified HTTPS. No server, no analytics, no location permission. MIT licensed.
