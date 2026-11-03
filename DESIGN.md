# DerYurt — DESIGN.md (DerFurt Tasarım Sistemi)

DerFurt'un uygulamalarında ortak kullanılacak tasarım dili. DerNotes'ta (2.5, Ekim 2026) uygulanıp cihazda denenmiş kurallardan çıkarıldı.

**Yeni bir projede nasıl kullanılır:**
1. Bu dosyayı projenin köküne `DESIGN.md` olarak kopyala.
2. `⟨…⟩` ile işaretli boşlukları o uygulamaya göre doldur. Önce §0'daki uygulama ayarları gelir.
3. Kodlayan ajana (Claude, Gemini…) ilk iş olarak bu dosyayı okumasını söyle. §6'daki kurallar pazarlıksızdır.

Değerler Android (dp/sp, `res/values`) için yazıldı. Web veya WebView tarafında aynı değerler CSS değişkeni olarak (px) kullanılır.

---

## 0. Uygulamaya göre doldurulacaklar

| Ayar | Değer | Not |
|---|---|---|
| Önek | `dy` | Token, stil ve bileşen adlarının başı. Örnek: `dn_surface`, `TextAppearance.DerNotes.Body`, `DnToast`. Aşağıda `xx` diye geçer. |
| Tema (vurgu) rengi | Kullanıcı seçer: turuncu (varsayılan), mavi, yeşil, kırmızı. `ui/AppTheme.kt` | Kullanıcı seçiyorsa her tema için `primary`, `accent` (açık ton) ve `stroke` (kart kenarı tonu) tanımlanır. Tek bir yardımcıdan okunur, örneğin `AppTheme.getThemeColors()`. |
| Açık / koyu | İkisi de var, **varsayılan koyu**. Ayarlar → Görünüm → "Koyu tema". Açık değerler `values/colors.xml`, koyu değerler `values-night/colors.xml`. | Aşağıdaki nötr renkler **koyu tema** içindir. Açık tema gerekirse aynı rollerle ayrı bir değer seti yazılır, roller değişmez. |
| Yazı tipi | Nunito (değişken font, `res/font/nunito_family.xml`) | Değişken (variable) font önerilir. Bütün kalınlıklar tek dosyadan gelir. |
| Uygulamaya özel bileşenler | Durum kartı (§5) | §5'e eklenir (DerNotes'ta not kartı, formül kutusu gibi). |

---

## 1. Renkler

### 1.1 Nötr roller (koyu tema)
| Token | Değer | Kullanım |
|---|---|---|
| `xx_bg` | `#121212` | Ekran zemini, status bar, navigation bar |
| `xx_surface` | `#1E1E1E` | Üst bar, kartlar, ayar kartları, giriş kutusu zemini |
| `xx_surface_raised` | `#262626` | Diyalog, popup menü, alttan açılan pencere, toast |
| `xx_input_bg_raised` | `#1B1B1B` | Diyalog/alttan pencere **içindeki** giriş kutusu ve kartlar (zeminden bir ton koyu) |
| `xx_outline` | `#333333` | Ayraç çizgisi, toast ince çerçevesi |
| `xx_input_outline` | `#2E2E2E` | Giriş kutusu çerçevesi, popup çerçevesi, basılı popup satırı |
| `xx_handle` | `#555555` | Alttan pencere tutamacı |
| `xx_text_primary` | `#EEEEEE` | Ana yazı, başlıklar |
| `xx_text_secondary` | `#AAAAAA` | İkincil yazı, açıklama, ikon |
| `xx_text_tertiary` | `#777777` | Soluk yazı: ipucu (hint), pasif, devre dışı |
| `xx_on_primary` | `#FFFFFF` | Tema renkli dolu buton/çip üstündeki yazı |
| `xx_on_error` | `#1A0B0A` | Yıkıcı (kırmızı dolu) buton üstündeki yazı |
| `xx_scrim` | `#99000000` | Diyalog/alttan pencere arkasındaki karartma |

### 1.2 Durum renkleri ("Yumuşak" set)
| Token | Değer | Kullanım |
|---|---|---|
| `xx_success` | `#5CC27A` | Başarı ikonu, tamamlanan işlem |
| `xx_error` | `#FF6B5E` | Hata, geri alınamaz işlem ("Sil") |
| `xx_warning` | `#FFB547` | Uyarı, enerji/kota göstergeleri |

Uygulamada tek bir kırmızı vardır: `xx_error`. Birden fazla kırmızı tonu kullanılmaz.

### 1.3 Vurgu (tema) rengi
- `primary`: dolu butonlar, seçili öğe, imleç, odak çerçevesi, ilerleme çizgisi.
- `accent`: tonlu zemin üstündeki yazı, tarih gibi vurgulu küçük yazılar.
- **Tonlu zemin** (ikincil buton, seçili segment): `blend(xx_surface, primary, %16)` ile hesaplanır, yazısı `accent`. Bu değer tek bir yardımcıdan üretilir (örneğin `AppTheme.tonalBackground()`); elle hesaplanmaz.
- Vurgu rengi koda sabit yazılmaz. Layout'ta varsayılan değer gerekirse token kullanılır, çalışma anında tema rengiyle değiştirilir.

### 1.4 Sabit kurallar
- Üst bar `xx_surface`, altında 4dp gölge. Status bar ve zemin `xx_bg`.
- Material 3 kullanılıyorsa temada `elevationOverlayColor = transparent` olmalı. Yoksa kart zemini `colorSurface` ile aynıyken kartlar tema rengine boyanır (DerNotes'ta yaşandı).

---

## 2. Yazı

- **Tek yazı tipi**, her yerde: ekranlar, web görünümleri, diyaloglar.
  - Android'de font temaya `android:fontFamily` olarak **verilmez**. O zaman `TextAppearance`'taki kalınlık ezilir ve kalın yazılar ince çizilir (DerNotes'ta yaşandı). Font, temanın `android:textAppearance` değeri ve Material `textAppearance*` eşlemeleri üzerinden verilir.
  - Kalın yazılar gerçek font kalınlığıyla çizilmeli, sahte (sentetik) kalınla değil. En düşük desteklenen Android sürümünde cihazda kontrol edilir.
  - Web tarafında fontun tam karakter kümesi kullanılır: Türkçe `ğ ş İ Ğ Ş` dahil. "latin" alt kümesi bu harfleri içermez.
- **Boyut ölçeği (sp), yalnızca bu değerler:**

| Stil (`TextAppearance.DerYurt.*`) | sp | Kalınlık | Kullanım |
|---|---|---|---|
| `Display` | 24 | ExtraBold (800) | Karşılama/tanıtım başlığı |
| `ScreenTitle` | 20 | ExtraBold (800) | Ekran başlıkları, alttan pencere büyük başlığı |
| `Title` | 18 | Bold (700) | Kart başlığı, diyalog başlığı, içerik adı |
| `Body` | 16 | Regular (400) | Ayar satırı başlığı, giriş kutusu, ana metin |
| `Body.Strong` | 16 | SemiBold (600) | Alttan pencere başlığı, seçenek başlığı |
| `BodySmall` | 14 | Regular (400) | Menü, açıklama, toast |
| `BodySmall.Medium` | 14 | Medium (500) | Bölüm başlıkları ("Tema Ayarları") |
| `BodySmall.Strong` | 14 | Bold (700) | Buton yazısı |
| `Caption` | 12 | Medium (500) | Tarih, sayaç, rozet, çip |
| `Micro` | 10 | SemiBold (600) | İkon altı etiketler, küçük rozet, sayaç rakamı |

- Layout'ta `android:textSize` doğrudan yazılmaz; `textAppearance` kullanılır. Renk gerekiyorsa `textColor` ayrıca verilebilir.
- Ölçeğe uymayan eski değerler en yakın stile çekilir: 11–12.5 → `Caption`, 10.5 → `Micro`, 15 kalın → `Body.Strong`.
- Uygulamaya özel tek bir istisna olabilir (DerNotes'ta kart önizlemesi 13sp `CardPreview`). İstisna burada yazılı değilse eklenmez.

---

## 3. Şekil ve boşluk

- **Köşe yuvarlaklığı, yalnızca bunlar:**
  - `xx_radius_s` = **8dp**: rozet, etiket, segment içi, giriş kutusu, küçük ikon kutusu
  - `xx_radius_m` = **16dp**: kartlar, ayar kartları, popup menü, buton, FAB
  - `xx_radius_l` = **24dp**: diyalog, alttan açılan pencere (sadece üst köşeler)
  - `xx_radius_full` = **999dp** (tam yuvarlak): toast, çip, hap rozet, switch
- **Boşluk: 4'lük ızgara.** `xx_space_1..8` = 4, 8, 12, 16, 24, 32dp. Başka değer yazılmaz.
  - Ekran kenar boşluğu her yerde **16dp**; kartlar arası boşluk da 16dp.
  - Kart iç boşluğu 12dp, diyalog iç boşluğu 24dp.
  - Ölçek dışı eski değerler en yakına çekilir: 2,3 → 4; 6,7 → 8; 10 → 12; 14,18 → 16; 20 → 24.
  - Yapısal ölçüler istisnadır: FAB için liste altı boşluğu, ikon genişliğine hizalı ayraç girintisi gibi. Bunlar bir bileşen boyutundan türer, ızgaraya zorlanmaz.
- Dokunma alanı en az 48dp. Görünen öğe daha küçük olabilir.

---

## 4. Ortak bileşenler

Her bileşen **tek bir yardımcıdan** üretilir; ekranlar onu çağırır, kendi kopyasını yazmaz. Önerilen adlar (`Xx` = önek): `XxToast`, `XxDialog`, `XxButton`, `XxInput`, `XxPopup`.

### Toast: "Hap"
- Sistem toast'ı (`Toast.makeText`) kullanılmaz; tek bileşen: `XxToast.show(activity, mesaj, tür, süre, aksiyon?)`.
- Ekranın altında ortada, tam yuvarlak. Zemin `xx_surface_raised`, 1dp `xx_outline` çerçeve, gölge.
- Solda 20dp ikon: başarı ✓ `xx_success`, hata ! `xx_error`, bilgi i `primary`, enerji/özel ⚡ `xx_warning`.
- Yazı `BodySmall` `xx_text_primary`, en fazla 2 satır. İsteğe bağlı aksiyon ("Geri al") sağda, `primary`, kalın, zeminsiz.
- Süreli aksiyonlarda ("Geri al", 5 sn) hapın alt kenarında 2dp, `primary` renkli, soldan sağa dolan ince çizgi.
- Görünürken FAB hap yüksekliği kadar yukarı kayar. Toast alt barları, klavyeyi ve navigation bar'ı örtmez.
- Aynı anda tek toast olur; yenisi eskisini kapatır.
- Ekran kapanırken gösterilen toast (ör. "Silindi" + geri) kalan süresiyle bir sonraki ekranda gösterilir. Uygulama arka plandaysa gösterilmez.
- Başka uygulamanın üstünde çalışan saydam ekranlar tek istisnadır: orada sistem toast'ı tek bir yardımcı fonksiyondan (`showSystem`) gösterilir.

### Butonlar: "Tonlu ikincil"
- Yükseklik 48dp, köşe `xx_radius_m`, yazı `BodySmall.Strong`, büyük harfe çevrilmez. Tek bir stil (`Widget.DerYurt.Button`) ve renkleri veren tek bir yardımcı.
- **Ana:** dolu `primary`, yazı `xx_on_primary`.
- **İkincil:** çerçevesiz, tonlu zemin, yazı `accent`.
- **Metin:** zeminsiz, yazı `primary`. Bilinçli olarak geri planda kalacaksa (ör. "Atla") `xx_text_secondary`.
- **Yıkıcı:** dolu `xx_error`, yazı `xx_on_error`.
- **Seçim grubu (segment):** seçili olan tonlu zemin + `accent`, diğerleri `xx_surface` + `xx_text_secondary`.

### Diyalog: "İki dolu buton"
- Zemin `xx_surface_raised`, köşe `xx_radius_l`, iç boşluk 24dp, arkada `xx_scrim`.
- Başlık `Title`, metin `BodySmall` `xx_text_secondary`. Özel içerik (giriş kutusu, seçenek listesi) başlık/metin ile butonlar arasına girer.
- Altta yan yana, eşit genişlikte iki buton: solda iptal = ikincil, sağda onay = ana. Geri alınamaz işlemde onay = yıkıcı.
- Tek seçenekli bilgi diyaloğunda tek ana buton, tam genişlik.
- Tek seçimli listeler (radyo) diyaloğun içine çizilir; seçip onaylayınca uygulanır.
- Bütün diyaloglar tek bir yardımcıdan üretilir. Çıplak `AlertDialog.Builder` kullanılmaz, buton renkleri elle boyanmaz.
- Aynı soru popup içinde soruluyorsa (ör. "taşınsın mı?") butonlar yine diyalogdaki gibidir.

### Giriş kutusu: "Dolu kutu"
- Yükseklik 48dp, köşe `xx_radius_s`, zemin `xx_surface` (diyalog/alttan pencere içinde `xx_input_bg_raised`), 1dp `xx_input_outline`. Odakta `primary` çerçeve, görsel kalınlık 2dp.
- Yazı `Body`, ipucu `xx_text_tertiary`, imleç `primary`.
- Üst barın parçası olan alanlar bu kurala girmez: arama kutusu, içerik başlığı, ana metin alanı. Çerçevesiz kalırlar, sadece imleç `primary` olur.

### Popup menü
- Zemin `xx_surface_raised`, 1dp `xx_input_outline` çerçeve, köşe `xx_radius_m`, gölge, iç boşluk 6dp.
- Satır: isteğe bağlı 18dp ikon `xx_text_secondary` + yazı `BodySmall`. Basılınca hafif aydınlanır (`xx_input_outline`, köşe 10dp).
- Yıkıcı satır ("Sil") en altta, ayraçla (`xx_outline`) ayrılır, ikon ve yazı `xx_error`.
- Sistemin hazır `PopupMenu`'sü kullanılmaz; bütün menüler aynı yardımcıyla açılır.

### Alttan açılan pencere
- Zemin `xx_surface_raised`, üst köşeler `xx_radius_l`, ortada 36×4dp `xx_handle` tutamaç. Material'in kendi zemini şeffaf yapılır.
- Başlık `Body.Strong` (gerekirse `ScreenTitle`), kenar boşluğu 16–24dp.
- İçindeki kartlar zeminden bir ton koyu: `xx_input_bg_raised` + 1dp `xx_input_outline`. Zeminle aynı renk olursa kaybolurlar.
- Ana buton en altta, tam genişlik.

### Web görünümü (WebView) içindeki bileşenler
- Renkler yalnızca `:root` CSS değişkenlerinde tanımlıdır (`--xx-bg`, `--xx-primary`…). Uygulama tema rengini bu değişkenlere yazar.
- Toast ve diğer bileşenler aynı görünümü bir CSS sınıfıyla (`.xx-toast`) taşır.
- Yazdırma/PDF renkleri de değişkendir (`--xx-print-text` vb.).
- Ekrana sığmayan geniş içerik (formül, tablo, kod) sağ kenarda düz kesilmez:
  - Önce en fazla %80'e küçülür.
  - Yine sığmazsa sağ kenar solar (32px maske); sona kaydırınca solma kalkar.
  - Bu ayarlar kaydedilen içeriğe yazılmaz.

---

## 5. Uygulamaya özel bileşenler
**Durum kartı** (ana ekran): zemin `dy_surface`, köşe `dy_radius_m`, ortalı. 72dp daire içinde 36dp ikon; daire zemini `tonal(durum rengi)`, ikon durum rengi (başarı `dy_success`, hata `dy_error`, captcha/yavaş `dy_warning`, GSB dışı `dy_text_secondary`, giriş sürüyor `accent`). Başlık `Title`, açıklama `BodySmall` `dy_text_secondary`. Altta tonlu (sol) + dolu (sağ) buton; biri yoksa diğeri tam genişlik. Giriş sürerken butonlar yerine tema renkli belirsiz ilerleme çizgisi.

**Açık tema notu:** Açık temada `accent` tonları koyulaştırıldı (tonlu zeminde okunsun diye); durum renkleri de açık zeminde okunacak tonlarda.

---

## 6. Ajanlar için zorunlu kurallar
1. **Hex renk yazılmaz:** layout, drawable, Kotlin/Java, JS (`#…`, `0xFF…`, `Color.parseColor`, `Color.WHITE/GRAY/BLACK`). Renkler yalnızca `colors.xml` token'larından, tema renkleri tek yardımcıdan gelir. Web tarafında renkler yalnızca `:root` değişkenlerindedir.
2. Yazı boyutu yalnızca `TextAppearance.*` stilleriyle; köşe ve boşluk yalnızca `xx_radius_*` / `xx_space_*` ile.
3. Sistem toast'ı, çıplak `AlertDialog.Builder` ve hazır `PopupMenu` kullanılmaz; ortak bileşenler kullanılır.
4. İkon uydurulmaz. Resmi kaynaktan alınır (Material Symbols / Android Studio Vector Asset) ya da projede olan kullanılır; kaynağı raporda yazılır.
5. Bu dosyada olmayan bir görsel karar gerekiyorsa **dur ve sahibine sor** (a/b/c seçenekleriyle, "kullanıcı ne görür" diliyle). "Makul" diye kendin seçme.
6. Görsel değişiklikten sonra "hangi ekranda neye bakılmalı" listesi verilir. Mümkünse bütün tema renklerinde cihazda ekran görüntüsüyle kontrol edilir.
7. Cihazda otomatik test yapılırken her dokunuştan önce ön plandaki pencerenin test edilen uygulama olduğu kontrol edilir. Gelen bir bildirim dokunuşu başka bir uygulamaya kaydırabilir.

---

## 7. Yeni projede kurulum sırası (öneri)
1. **Altyapı:** renk token'ları, `dimens` (köşe + boşluk), `TextAppearance` stilleri, font (temaya `textAppearance` yoluyla), web tarafında `:root` değişkenleri.
2. **Ortak bileşenler:** Toast, Diyalog, Buton, Giriş kutusu, Popup, Alttan pencere.
3. **Ekran ekran ince ayar:** yazı boyutları stile, boşluklar ızgaraya, köşeler ölçeğe.
4. **Kontrol:** hex taraması (`grep`), `textSize` taraması, bütün tema renklerinde ekran görüntüsü.
