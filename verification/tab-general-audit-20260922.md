# TAB genel inceleme — 22 Eylül 2026

Kaynak: `6cb9ec191` üzerine yerel düzeltmeler. Önceki scoreboard incelemesi bu çalışmanın ilk aşamasıdır; son jar hashleri ve test sonuçları bu dosyadadır.

## Düzeltilen sorunlar

1. Değişmeyen scoreboard başlığı/takım özelliklerinin tekrar platform güncellemesine gitmesi engellendi. İstemci durumunu yeniden kuran `resend()` korunuyor.
2. Uzun satırın yalnızca skoru veya prefix/suffix'i değişince skor/takım silip yeniden ekleme kaldırıldı. Entry kimliği değişirse gerekli yeniden oluşturma devam ediyor.
3. Paket kuyruğunda drain sırasında tekrar boş görev planlama giderildi; son poll sonrası eklenen paketler yeniden planlanıyor. Kapalı kanal/reddedilen executor durumunda referans sayımlı paketler bırakılıyor.
4. Placeholder sonuçları kaydedilirken player/server/relational üst placeholder'ların ikinci kez yenilenmesi kaldırıldı. İç içe zincirlerde üst seviyeye yinelenen yayılım kaldırıldı.
5. Aynı özellik/oyuncu için normal ve zorunlu refresh birlikte geldiyse yalnızca zorunlu refresh çalışıyor. Kuyrukta beklerken çıkan oyuncular atlanıyor.
6. Döngüsel placeholder çözümleme ve üst placeholder güncellemelerine thread-local döngü koruması eklendi; kapsam bitince temizleniyor. Geçerli zincir ve döngüden sonra geçerli güncelleme test edildi.
7. Placeholder ilk değerinin raw/evaluated map'ler arasındaki yayınlanma aralığında ikinci okuyucunun null dönmesi düzeltildi.
8. Kullanılan özellikler kümesi eşzamanlı iterasyona uygun hale getirildi; parent ekleme tekrarsız ve current handle yayını volatile oldu. Mevcut getter tipleri korundu.
9. Sıfır, geçersiz negatif ve 50ms'nin katı olmayan refresh süreleri doğrulanıyor. Geçersiz config default/permission süreleri güvenli değere dönüyor; API geçersiz süreyi reddediyor. Permission `-1` artık periyodik group görevi planlamıyor.
10. CPU takibi kapalıyken her görev/placeholder çağrısındaki gereksiz nanoTime ölçümleri kaldırıldı. Yavaş placeholder uyarı mekanizması korunuyor.
11. ThreadExecutor kendi worker'ından kapanırken kendi bitişini beklemiyor; kalan işler atılıyor. Kesilme durumunda interrupt korunuyor ve executor durduruluyor.
12. Velocity çıkış işlemi oyuncu UUID'sinin yanında bağlantı nesnesini doğruluyor. Eski bağlantı yeni oturumu silemiyor; reload ile yüklenen oyuncuların çıkışı da işleniyor. İşlenmeden önce kapanmış connect olayı hayalet oyuncu oluşturmuyor.
13. Bossbar'da aynı title/progress/style/color tekrar gönderilmiyor; ikinci freeze ilk freeze öncesi bar listesini ezmiyor. Böylece frozen halde silinen bar sunucu değişimi sonrası kalmıyor.
14. Tablist display-name desteği hedef oyuncunun değil paketi alan oyuncunun protokolüne göre kontrol ediliyor.
15. MySQL sorgusu executeQuery aşamasında hata verse de statement kapanıyor.
16. Boş/bilinmeyen bridge mesaj ID'leri dizi hatası üretmeden eleniyor.
17. Upstream `f2ea34a71`: placeholder manager henüz oluşmadan yapılan header/footer config dönüşümündeki null hatası düzeltmesi alındı.

## İncelenen diğer alanlar

Header/footer, playerlist, sorting, nametag/multi-line renderer, belowname, layout, spectator, global playerlist, proxy messaging, Bukkit sync placeholder, cache, skin kaynakları, config/MySQL, görev ve kapanış akışları incelendi. Bu, bütün olası hataların bulunmuş olduğu iddiası değildir.

- Header/footer ve nametag metinleri zaten değişiklik karşılaştırması kullanıyor. Gereken hareket, giriş ve sunucu değişimi paketleri korunuyor.
- Canlı yapılandırmada TAB lobi scoreboard'ını, Bolt maç/parti/kuyruk tablolarını yönetiyor. Bossbar/layout/belowname kapalı; kullanılmayan özellikleri kaynak koddan silmek yerine mevcut kapalı davranış korundu.
- Skin indirme cache miss sırasında senkron çalışıyor ve mevcut 5 saniyelik bağlantı/okuma timeout'larına sahip. Ağ gecikmesiyle test edilmedi; gelecekte asenkron tasarım ayrı bir davranış değişikliği gerektirir.
- Relational placeholder değerlendirmesi oyuncu çiftleri üzerinden çalışıyor. Görünmeyen çiftleri körlemesine atlamak farklı world/server gösterimlerini bozabileceği için değiştirilmedi. Canlı profil ile hangi placeholder'ın pahalı olduğu ölçülmeli.
- 14 upstream commit tek tek karşılaştırıldı. Diğerleri yeni Minecraft platformları, sürüm/Gradle/bStats/badge ve API deprecation değişiklikleri; bu slim 1.8/Velocity forkuna topluca merge edilmedi.

## Doğrulama

Amazon Corretto 25.0.4; repository toolchain/release ayarları korunarak:

```text
gradlew.bat :shared:test :velocity:test :bukkit:v1_8_R3:shadowJar :velocity:shadowJar --offline
git diff --check
```

Sonuç: **36 test, sıfır hata** (shared 34, Velocity 2). Bukkit ve Velocity jarları üretildi. Üretim jarında JUnit/Mockito/test sınıfı yok. Canlı sürümdeki `NMSMultiLineRenderer` ve bütün iç sınıfları byte-for-byte aynı; mevcut yakın mesafe/hitbox düzeltmeleri korundu.

Jarlar:

- `bukkit/v1_8_R3/build/libs/TAB-v1_8_R3-6.2.0-SNAPSHOT.jar`
  SHA-256: `8a78e0d25c7dd6cbdfc62760be08d83d6f26b6ba1fc225106818b8aa8b430797`
- `velocity/build/libs/TAB-velocity-6.2.0-SNAPSHOT.jar`
  SHA-256: `8562eafe1449d751873861f5b38ff33786ac6bc1d44813ce6f17f4e1fc403f69`

Canlı oyuncu yüküyle önce/sonra spark veya paket sayımı yapılmadı. Performans yüzdesi veya bütün lagın giderildiği iddia edilmiyor. Carbon değiştirilmedi.

## Sunucuya aktarım

Practice volume `6dd161d9-ff6f-4d33-9b3b-12204175581f` içinde Bukkit jarı `plugins/update/TAB-v1_8_R3-6.2.0-SNAPSHOT.jar` olarak yerleştirildi. SHA-256 sunucuda tekrar doğrulandı. `bukkit.yml` update-folder değeri `update`.

Önceki jar yedeği: `plugins/TAB-v1_8_R3-6.2.0-SNAPSHOT.jar.bak-general-audit-20260922-001716`; hash'i de doğrulandı. Sunucu yeniden başlatılmadı: güncelleme sıradaki yeniden başlatmada uygulanacak. Velocity jarı yerelde hazır; proxyye kurulmadı.
