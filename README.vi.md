<p align="center">
  <img src="docs/ads.png" alt="Aether — Tự do, chỉ với một chạm" width="100%">
</p>

<h1 align="center">Aether Mobile</h1>

<p align="center">
  Ứng dụng Android <b>Material You</b> nền tối tuyệt đẹp cho engine Aether — với
  lõi tạo tunnel được xây dựng <b>ngay bên trong app</b>, nên bạn không còn cần v2rayNG nữa.
</p>

<p align="center">
  <a href="README.vi.md">🇻🇳 Tiếng Việt</a> · <a href="README.md">🇬🇧 English</a> · <a href="README.fa.md">🇮🇷 فارسی</a>
</p>

<p align="center">
  <img alt="version" src="https://img.shields.io/badge/version-1.4.0-8B5CF6?style=flat-square">
  <img alt="engine core" src="https://img.shields.io/badge/engine%20core-2.1.0-22D3EE?style=flat-square">
  <img alt="min Android" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square">
  <img alt="license" src="https://img.shields.io/badge/license-AGPL--3.0-F59E0B?style=flat-square">
</p>

---

## Có gì mới trong v1.4.0

Một bản phát hành bảo trì được xây dựng hoàn toàn từ các báo cáo thực tế của
bản 1.3.0, cộng thêm một chế độ tự động mới. Chi tiết đầy đủ trong
[`CHANGELOG.md`](CHANGELOG.md), chi tiết từng file trong
[`PATCH_NOTES_1.4.0.md`](PATCH_NOTES_1.4.0.md).

### Các trang chia sẻ VPN được thiết kế lại (tiếng Ba Tư và tiếng Anh)

Các trang mà thiết bị được chia sẻ nhìn thấy, `http://aether.check/` và trang
thiết lập tại `http://<phone>:10811/`, từng là một mớ tiếng Anh lẫn tiếng Ba Tư
lộn xộn, vỡ layout trên điện thoại. Chúng đã được xây dựng lại:

- **Một layout nền xanh navy tối duy nhất** hiển thị tốt trên điện thoại, laptop
  và trình duyệt TV.
- **Nút chuyển ngôn ngữ ở phía trên:** فارسی (mặc định) hoặc English. Lựa chọn
  được ghi nhớ trong trình duyệt đó.
- **Tiếng Ba Tư hiển thị từ phải sang trái toàn bộ**, dùng font Vazirmatn /
  Vazir nếu thiết bị có, và font tiếng Ba Tư của hệ thống nếu không. Mọi từ
  tiếng Anh, địa chỉ, cổng và tên file trong câu tiếng Ba Tư vẫn giữ nguyên khối
  từ trái sang phải, nên không bị xáo trộn.
- **Tiếng Ba Tư đơn giản, hướng dẫn từng bước:** nút copy cho các địa chỉ
  PAC / HTTP / SOCKS5, các bước thiết lập cho Windows, macOS, Android, iPhone,
  Firefox và TV, cùng hướng dẫn cho **cả hai chế độ chia sẻ, Wi-Fi gia đình và
  điểm phát sóng (hotspot) của điện thoại**, với chế độ điện thoại đang dùng
  được mở sẵn và đánh dấu.
- Bài kiểm tra WebRTC trực tiếp và các nút khóa WebRTC một chạm vẫn hoạt động
  như trước, nay được giải thích bằng cả hai ngôn ngữ.
- Các trang không tải bất cứ thứ gì từ internet và không bao giờ hiển thị mật
  khẩu chia sẻ.

### 🔒 Kiểm toán bảo mật 1.4.0: **92 / 100**

Một đợt kiểm toán 0-100 mới cho toàn bộ app, lần đầu tiên chấm điểm riêng khu
vực chia sẻ qua LAN. Báo cáo đầy đủ:
[`docs/SECURITY_AUDIT_1.4.0.md`](docs/SECURITY_AUDIT_1.4.0.md).
Phiên bản **1.4.0** (version code 16), engine **2.1.0**. **Chữ ký không đổi**, nên
1.4.0 cài đè trực tiếp lên 1.3.0, không cần gỡ cài đặt.

| # | Hạng mục | Trọng số | Điểm | Điểm trọng số |
| --- | --- | ---: | ---: | ---: |
| 1 | Quản lý bí mật & khóa | 14 | 95 | 13.30 |
| 2 | Mật mã học, TLS & khả năng chống MitM | 13 | 95 | 12.35 |
| 3 | Nguy cơ rò rỉ dữ liệu (DNS, IPv6, lách tunnel) | 17 | 93 | 15.81 |
| 4 | Bề mặt chia sẻ qua LAN (proxy, các trang, WebRTC) | 10 | 86 | 8.60 |
| 5 | Lưu trữ cục bộ (dữ liệu nghỉ) | 12 | 94 | 11.28 |
| 6 | Quyền & cấu hình hệ điều hành | 7 | 98 | 6.86 |
| 7 | Ghi log & chẩn đoán | 7 | 96 | 6.72 |
| 8 | Chất lượng code & cấu hình mạng | 8 | 84 | 6.72 |
| 9 | Lộ thông tin trên thiết bị (màn hình, clipboard) | 6 | 92 | 5.52 |
| 10 | Chuỗi cung ứng & tính toàn vẹn của bản build | 6 | 80 | 4.80 |
| | **Tổng** | **100** | | **91.96 ≈ 92** |

| ID | Mức độ | Phát hiện | Trạng thái |
| --- | --- | --- | --- |
| SA-1 | Trung bình | Chia sẻ qua LAN mặc định không có mật khẩu, nên bất kỳ thiết bị nào trên cùng Wi-Fi hoặc cùng subnet hotspot đều có thể dùng tunnel | Mở theo quyết định sản phẩm; trang thiết lập nay đã nêu rõ điều này và hướng dẫn người dùng khi nào nên bật mật khẩu |
| SA-2 | Thấp | Các trang chia sẻ không có Content-Security-Policy hay referrer policy, địa chỉ điện thoại không được HTML-escape, và kết quả WebRTC được ghi bằng dữ liệu candidate thô | **Đã sửa trong 1.4.0** |
| SA-3 | Thấp | Thông tin đăng nhập chia sẻ truyền ở dạng rõ trên LAN (HTTP Basic / SOCKS5 RFC 1929), vốn là đặc tính của cả hai giao thức | Chấp nhận, chỉ trong cùng subnet |
| SA-4 | Thấp | Ở chế độ hotspot, WebRTC của thiết bị khách đi qua tethering NAT của Android, mà app không root không thể lọc được | Đã giảm thiểu (kiểm tra trực tiếp + khóa một chạm), chấp nhận |
| SA-5 | Thấp | R8 / rút gọn code (minification) đang tắt cho bản build release | Mở (chủ ý, không đổi) |
| SA-6 | Thấp | Các CI action được ghim theo tag; `dtolnay/rust-toolchain@stable` bám theo một nhánh di động | Mở |
| SA-7 | Thông tin | Không ghim chứng chỉ; chỉ tin tưởng CA của hệ thống (từ chối CA do người dùng cài) | Chấp nhận |
| SA-8 | Thông tin | Chữ ký app nằm ngoài phạm vi kiểm toán: 1.4.0 giữ nguyên đúng định danh chữ ký của 1.3.0 để cài đè lên app hiện tại mà không cần gỡ | Không chấm điểm |

<details>
<summary>🇻🇳 Bản kiểm toán này bằng tiếng Việt</summary>

### 🔒 Kiểm toán bảo mật 1.4.0: **92 / 100**

Một đợt kiểm toán 0-100 mới cho toàn bộ app, lần đầu tiên chấm điểm riêng khu
vực chia sẻ qua LAN. Báo cáo đầy đủ:
[`docs/SECURITY_AUDIT_1.4.0.md`](docs/SECURITY_AUDIT_1.4.0.md).
Phiên bản **1.4.0** (version code 16), engine **2.1.0**. **Chữ ký không đổi**, nên
1.4.0 cài đè trực tiếp lên 1.3.0, không cần gỡ cài đặt.

| # | Hạng mục | Trọng số | Điểm | Điểm trọng số |
| --- | --- | ---: | ---: | ---: |
| 1 | Quản lý bí mật & khóa | 14 | 95 | 13.30 |
| 2 | Mật mã học, TLS & khả năng chống MitM | 13 | 95 | 12.35 |
| 3 | Nguy cơ rò rỉ dữ liệu (DNS, IPv6, lách tunnel) | 17 | 93 | 15.81 |
| 4 | Bề mặt chia sẻ qua LAN (proxy, các trang, WebRTC) | 10 | 86 | 8.60 |
| 5 | Lưu trữ cục bộ (dữ liệu nghỉ) | 12 | 94 | 11.28 |
| 6 | Quyền & cấu hình hệ điều hành | 7 | 98 | 6.86 |
| 7 | Ghi log & chẩn đoán | 7 | 96 | 6.72 |
| 8 | Chất lượng code & cấu hình mạng | 8 | 84 | 6.72 |
| 9 | Lộ thông tin trên thiết bị (màn hình, clipboard) | 6 | 92 | 5.52 |
| 10 | Chuỗi cung ứng & tính toàn vẹn của bản build | 6 | 80 | 4.80 |
| | **Tổng** | **100** | | **91.96 ≈ 92** |

| ID | Mức độ | Phát hiện | Trạng thái |
| --- | --- | --- | --- |
| SA-1 | Trung bình | Chia sẻ qua LAN mặc định không có mật khẩu, nên bất kỳ thiết bị nào trên cùng Wi-Fi hoặc cùng subnet hotspot đều có thể dùng tunnel | Mở theo quyết định sản phẩm; trang thiết lập nay đã nêu rõ điều này và hướng dẫn người dùng khi nào nên bật mật khẩu |
| SA-2 | Thấp | Các trang chia sẻ không có Content-Security-Policy hay referrer policy, địa chỉ điện thoại không được HTML-escape, và kết quả WebRTC được ghi bằng dữ liệu candidate thô | **Đã sửa trong 1.4.0** |
| SA-3 | Thấp | Thông tin đăng nhập chia sẻ truyền ở dạng rõ trên LAN (HTTP Basic / SOCKS5 RFC 1929), vốn là đặc tính của cả hai giao thức | Chấp nhận, chỉ trong cùng subnet |
| SA-4 | Thấp | Ở chế độ hotspot, WebRTC của thiết bị khách đi qua tethering NAT của Android, mà app không root không thể lọc được | Đã giảm thiểu (kiểm tra trực tiếp + khóa một chạm), chấp nhận |
| SA-5 | Thấp | R8 / rút gọn code (minification) đang tắt cho bản build release | Mở (chủ ý, không đổi) |
| SA-6 | Thấp | Các CI action được ghim theo tag; `dtolnay/rust-toolchain@stable` bám theo một nhánh di động | Mở |
| SA-7 | Thông tin | Không ghim chứng chỉ; chỉ tin tưởng CA của hệ thống (từ chối CA do người dùng cài) | Chấp nhận |
| SA-8 | Thông tin | Chữ ký app nằm ngoài phạm vi kiểm toán: 1.4.0 giữ nguyên đúng định danh chữ ký của 1.3.0 để cài đè lên app hiện tại mà không cần gỡ | Không chấm điểm |

</details>

### Smart nhanh hơn, trên cùng một nút

**Smart** trước đây thử từng chiến lược một cho đến khi có cái hoạt động. Nó vẫn
làm vậy trên các backend ghép chuỗi — nhưng trên **Aether** thuần, giờ nó làm
thêm hai việc:

- **Thử hai tuyến đường cùng lúc** và giữ lại tuyến thực sự có traffic chạy qua.
  Thử lần lượt từng cái nghĩa là một phỏng đoán sai đầu tiên tốn trọn một phút;
  chạy đua thì không tốn gì, vì tuyến còn lại đã đang chạy.
- **Ghi nhớ tuyến nào đã hoạt động trên mạng này.** Lần kết nối sau trên cùng
  Wi-Fi hoặc cùng SIM, tuyến đó được thử trước, một mình — nên lần kết nối thứ
  hai thường chỉ mất vài giây và không cần khởi động engine thứ hai.

Tệ nhất giảm từ khoảng năm phút rưỡi xuống còn khoảng ba phút, và khi không có
gì trên mạng hoạt động được, bạn biết điều đó trong một phút thay vì năm phút.

**Không cần bật gì cả.** Giữ giao thức ở **Smart**. Dòng dưới bộ chọn cho bạn
biết profile này dùng kiểu nào: chế độ đua cần backend Aether thuần với chọn
endpoint tự động, nên `Aether → Psiphon`, `Aether → Tor`, endpoint ghim tay hay
Zero Trust với mã e-mail vẫn dùng thang thử lần lượt như trước.

| | Nó làm gì |
| --- | --- |
| **Smart** | Đo mạng của bạn, rồi đua hai tuyến trên Aether thuần (ghi nhớ tuyến đã thành công) hoặc thử lần lượt trên backend ghép chuỗi |
| **Chọn giao thức thủ công** (WireGuard, MASQUE, WARP×2, MASQUE×2) | Đúng những gì bạn chọn, không thêm gì khác |

**Cái giá.** Trong lúc dò tìm trên Aether thuần, hai engine chạy thay vì một,
nên giai đoạn kết nối đó tốn pin và data hơn. Khi đã kết nối, không có gì khác
biệt.

**Điều nó không làm được.** Mở một mạng đang bị đóng. Ở nơi điểm nghẽn là việc
đăng ký WARP của Cloudflare không thể truy cập được, chạy đua chỉ giúp bạn biết
nhanh hơn.

### Đã sửa trong 1.4.0

- **App không còn mất khoảng mười giây để mở.** Nó từng giải mã toàn bộ log
  chẩn đoán đã mã hóa trên main thread trước frame đầu tiên, mà log đó gồm hàng
  nghìn bản ghi niêm phong riêng lẻ, mỗi cái là một chuyến đi vào hardware
  keystore — rồi cuối cùng chỉ giữ 800 dòng mới nhất. Giờ việc khôi phục chạy
  dưới nền và chỉ phần đuôi được giữ lại mới bị giải mã. Đây cũng là nguyên nhân
  đằng sau các crash `ForegroundServiceDidNotStartInTimeException`: một main
  thread kẹt trong keystore không thể trả lời `startForegroundService()` kịp thời.
- **Widget không còn kẹt ở "Disconnecting…".** Hai nguyên nhân: bước cuối của quá
  trình ngắt kết nối không bao giờ vẽ lại nó, và trong tiến trình receiver lạnh,
  nó đọc một trạng thái kết nối chỉ tồn tại bên trong app đang chạy. Giờ nó là
  1×1 thay vì 3×1, thay đổi được kích thước, và biểu tượng nguồn lấy màu theo
  trạng thái. Nút hành động trên thông báo ghi "Disconnect" thay vì bị gắn nhãn
  bằng một trạng thái.
- **Private Space hoạt động.** Mọi cổng loopback đều là `const`, nên instance
  thứ hai trong một Android profile khác bị thua khi bind và thất bại trước cả
  khi tunnel chạy. File mới `core/PortLease.kt` phân giải chúng theo từng session
  — cổng ưu tiên khi còn trống, cổng trống tiếp theo nếu không — và app giờ
  truyền `--bind` / `--tor-bind`, những flag mà engine luôn chấp nhận nhưng app
  chưa từng dùng.
- **Endpoint đang hoạt động không còn bị vứt bỏ mỗi lần kết nối lại.**
  `fastEndpointOnly` mặc định bật với ngân sách hiệu chuẩn cho các edge 100–150
  ms; log thực tế không có gì dưới 727 ms, nên cache bị loại bỏ mỗi lần và mỗi
  lần kết nối lại phải trả giá một lần quét đầy đủ. Mặc định giờ là tắt, và các
  ngân sách nó gửi khi bật lên khớp với mạng thực.
- **Zero Trust: mã e-mail cuối cùng cũng được hỏi.** Engine in một prompt đọc
  được bằng máy ra stdout và chờ stdin; app chưa từng tìm nó. Một hộp thoại giờ
  xuất hiện giữa lúc kết nối. Token enrolment được kiểm tra ngay khi dán vào.
  Vẫn là xác thực *trong lúc* kết nối, không phải trước đó — lý do nằm trong
  [`docs/ZERO_TRUST.md`](docs/ZERO_TRUST.md).
- **Android TV**, **bản build x86_64**, **MTU có kiểu** (1280–9000), **nhận diện
  nhà mạng đúng trên điện thoại hai SIM**, **ECHConfigList dán vào**, và một lời
  nhắc khi Android vẫn được phép treo (suspend) app.
- **Hai bản sửa build:** kiểm tra tính năng Tor không còn vấp `pipefail` trên
  binary tốt, và lỗi `lint` duy nhất của dự án — từng có trong bản 1.3.0 đã phát
  hành — đã biến mất.
- **Phiên bản:** app 1.4.0, version code 16, engine core 2.1.0. Cài đè trực tiếp
  lên 1.3.0 — cấu hình chữ ký không đổi.

> **Đã xác minh, chưa thử nghiệm thực tế.** 1.4.0 biên dịch sạch, 88 unit test
> pass và `lint` không báo lỗi, nhưng chưa được chạy trên thiết bị thật. Bản sửa
> khởi động, bản sửa widget, bản sửa Private Space và bắt tay Zero Trust là bốn
> tuyên bố cần thiết bị để xác nhận.

## Trước đó trong v1.2.9

- **Hai rò rỉ quyền riêng tư thật đã được tìm ra và bịt trong lúc sửa crash đó.**
  Địa chỉ IPv6 ánh xạ IPv4 (`::ffff:203.0.113.9`) chỉ bị che một nửa, để lộ ba
  octet của địa chỉ công cộng trong digest, và các định danh JSON của Psiphon
  (`"sessionId":"…"`) hoàn toàn không được nhận diện là định danh. Cả hai giờ
  đã bị che, và bộ test của redactor tăng từ 8 lên 12 case.
- **Engine (core) nâng cấp lên v1.9.0** (trước: v1.8.0), với các patch engine của
  riêng app được rebase lên mã nguồn mới thay vì bị ghi đè:
  - **Bạn có thể tự đặt tên hai hop của WARP-in-WARP (gool).** Tùy chọn engine
    mới `--wiw-outer` / `--wiw-inner` / `--wiw-peers` (và `--wiw-scan` để quay
    lại săn cả hai). Đặt tên một hop giúp lần quét tìm ra hop còn lại, cổng phải
    được ghi rõ, và hai hop phải là các edge khác nhau. Địa chỉ sai định dạng
    giờ được báo trước khi cấp tài khoản thay vì lặng lẽ quay về quét.
  - **MASQUE trên HTTP/2 không còn bị giới hạn bởi chính phương tiện của nó.**
    Các cửa sổ flow-control của HTTP/2 ở mức tối thiểu RFC là 64 KB, giới hạn
    mọi lượt tải qua transport này ở khoảng 500 KB/s trên đường 130 ms cho dù
    đường truyền thực nhanh đến đâu. Các cửa sổ giờ theo hạng thiết bị, frame
    DATA có thể tới 64 KB, các gói outbound đã xếp hàng sau nhau được gửi trong
    một frame thay vì mỗi gói một frame, việc gửi chuyển sang task riêng (nên
    upload bận không còn làm nghẽn download bên cạnh), và tunnel HTTP/2 có MTU
    trong đầy đủ 1500 byte thay vì 1280 mà chỉ QUIC mới cần.
  - **Bộ đệm engine có thể tinh chỉnh theo thiết bị** mà không cần build lại
    (`AETHER_NETSTACK_TCP_RX`, `AETHER_NETSTACK_TCP_TX`, `AETHER_MASQUE_MTU`),
    và output `help` của engine giờ tài liệu hóa mọi tùy chọn kèm biến môi
    trường thiết lập nó.
- **Mọi thứ 1.2.8 đã sửa vẫn y nguyên.** Đây là điều kiện của đợt nâng cấp,
  không phải việc làm thêm cho có: core 1.9.0 độc lập đã đổi kích thước cùng các
  bộ đệm data-plane mà 1.2.8 tốn năm vòng mới chỉnh đúng, và những con số
  upstream đó đã **không** được lấy. Netstack giữ kiểm soát tắc nghẽn CUBIC, bộ
  đệm gửi uplink có giới hạn và cửa sổ nhận theo bandwidth-delay-product, các
  socket datagram giữ cách chia kích thước nhận/gửi từ r6, và TCP stack vẫn ghim
  đúng phiên bản mà netstack của app được viết theo. Tốc độ tải và hành vi kết
  nối trên mọi giao thức đều là của 1.2.8.
- **Lần nâng cấp core tự động tiếp theo an toàn hơn lần này.** Các bản sao
  upstream nguyên vẹn của *cả mười* file engine mà app patch nay được cache làm
  baseline merge (1.2.8 chỉ cache hai), nên rebase ba chiều của CI không bao giờ
  nhầm patch của app thành file bị upstream xóa.
- **Phiên bản:** app 1.2.9, version code 13, engine core 1.9.0. Cài đè trực tiếp
  lên 1.2.8 từ cùng repository — cấu hình chữ ký không đổi.

### 🔒 Kiểm toán bảo mật 1.2.9 — **79 / 100**

Một đợt kiểm toán bảo mật app di động đầy đủ đã chạy trên toàn bộ cây đã phát
hành (app Kotlin, Gradle + cấu hình ký, manifest, engine Rust, thư viện Psiphon
nhúng và CI). Báo cáo đầy đủ:
[`docs/SECURITY_AUDIT_1.2.9.md`](docs/SECURITY_AUDIT_1.2.9.md).

| # | Hạng mục | Trọng số | Điểm |
| --- | --- | ---: | ---: |
| 1 | Quản lý bí mật & khóa | 20 | 55 |
| 2 | Mật mã học & giao thức (TLS, MitM) | 15 | 88 |
| 3 | Nguy cơ rò rỉ dữ liệu (DNS, IPv6, lách) | 20 | 90 |
| 4 | Lưu trữ cục bộ | 15 | 72 |
| 5 | Quyền & cấu hình hệ điều hành | 10 | 96 |
| 6 | Ghi log | 10 | 85 |
| 7 | Chất lượng code & cấu hình mạng | 10 | 80 |
| | **Tổng trọng số** | **100** | **79** |

**Điểm tốt:** không có API key, token hay private key nào bị hardcode trong
app; Gemini key của người dùng và các bí mật Cloudflare Access được niêm phong
bằng AES-256-GCM dưới khóa Android Keystore không thể xuất; **không có
`TrustManager` tùy biến và không có hostname verifier dễ dãi** ở đâu cả, và mọi
socket TLS viết tay đều xác minh tên chứng chỉ tường minh, nên không tìm thấy
đường MitM nào; không có crypto yếu hay lỗi thời (không MD5/SHA-1/DES/RC4/ECB);
DNS được exit phân giải chứ không phải thiết bị, và IPv6 được định tuyến vào
tunnel (vô điều kiện ở chế độ ghép chuỗi) nên không có đường rò rỉ DNS hay IPv6;
HTTP cleartext bị từ chối trên toàn app; backup bị tắt hai lớp; chỉ xin bốn
quyền, không `QUERY_ALL_PACKAGES`, không `REQUEST_INSTALL_PACKAGES` và không
content provider nào exported; `android:debuggable` không bao giờ được set và
`allowBackup` là `false`; và app **không có analytics, không có SDK báo crash
và không có thư viện tracking** nào.

**Điểm bị trừ:** khóa ký release vẫn còn commit trong repository cùng mật khẩu
trong file build (**nghiêm trọng**, và là vấn đề duy nhất chiếm gần hết khoảng
cách điểm — cần xoay vòng khóa chứ không phải sửa code; bản build 1.2.9 đã làm
khóa này thành opt-in và cảnh báo lớn khi dùng); log chẩn đoán và file identity
của engine (chứa WireGuard private key) đang ở dạng plaintext trên bộ nhớ riêng
của app; chia sẻ qua LAN, khi người dùng bật, là một proxy không xác thực cho
mọi người trên Wi-Fi; không có certificate pinning; và R8/minification đang tắt.
Với khóa ký được xoay vòng và R8 bật, cùng cây mã này đạt ≈ 88.

### 🤖 Log và dữ liệu của bạn không được gửi cho AI

Đã nêu rõ ràng, vì đây là câu hỏi quan trọng: **app không gửi thông tin nhạy
cảm của bạn cho bất kỳ AI nào.**

- Các tính năng AI chỉ tồn tại nếu **bạn** dán **Gemini API key của chính mình**
  và bật chúng lên. Khi tính năng tắt — mặc định — app **không** gửi request nào
  tới bất kỳ dịch vụ AI nào.
- Thứ được gửi là **digest đã redacted** của log chẩn đoán (tối đa 220 dòng /
  12.000 ký tự), qua TLS, đi qua chính local proxy của tunnel — không bao giờ
  là log thô của bạn.
- Bị loại bỏ trước khi rời khỏi điện thoại: API key của bạn và mọi giá trị
  key/token/secret/password/authorization (ở dạng plain **và** JSON), handle
  enrolment `device=` của WARP, các định danh cài đặt và session (kể cả
  `"sessionId"` của Psiphon) và mọi UUID, địa chỉ IPv4 công cộng (che thành
  `188.114.x.x`), địa chỉ IPv6 công cộng (che thành `2606:4700:x:x` — IPv6 WARP
  được cấp cho bạn định tuyến toàn cầu và ổn định, nên địa chỉ đầy đủ *chính
  là* bạn), và địa chỉ IPv6 ánh xạ IPv4.
- Giữ lại có chủ ý, vì chúng là chi tiết kỹ thuật chứ không phải định danh:
  `127.0.0.1:1819`, các địa chỉ private/loopback, timestamp, text giao thức và
  lỗi, build stamp.
- **Không bao giờ gửi:** lịch sử duyệt web, truy vấn DNS, domain đã thăm, nội
  dung traffic hay packet payload, danh bạ, file, định danh thiết bị hay vị trí.
  App này không có telemetry.
- Nếu redactor thất bại trên một dòng, dòng đó bị **loại bỏ** — không bao giờ
  gửi ở dạng chưa redacted. Điều này được ép buộc trong code và được unit test
  bao phủ.

---

## Có gì mới trong v1.2.8

Người dùng báo cáo một số sự cố kết nối, đặc biệt khi dùng chế độ ghép chuỗi
(`Aether → Psiphon`). Các sự cố này đã được sửa:

- Ở chế độ ghép chuỗi, đôi khi chỉ Telegram và Instagram mở được. Trình duyệt
  và nhiều app khác không hoạt động.
- Gemini, ChatGPT và các app AI tương tự đôi khi báo không có kết nối internet.
- CapCut có thể không mở được hoặc hiệu ứng không tải. Giờ hoạt động được ở
  chế độ ghép chuỗi, bao gồm tải hiệu ứng.
- TikTok có thể không mở được khi chỉ dùng Aether. Khuyến nghị chế độ ghép
  chuỗi cho TikTok và CapCut.
- Đôi khi app hiển thị kết nối đang hoạt động, nhưng duyệt web và nội dung trực
  tiếp không chạy đúng.
- Trong lúc upload dài hoặc live session, kết nối có thể rất chậm hoặc ngừng
  truyền dữ liệu. Giờ nó xử lý tốt hơn và tự phục hồi.
- Ping và trạng thái kết nối không phải lúc nào cũng đáng tin. App giờ kiểm tra
  kết nối chính xác hơn và hiển thị trạng thái đáng tin cậy hơn.

Các bản sửa này áp dụng cho Aether thuần và, ở những chỗ liên quan, cho
`Aether → Psiphon`.

---

## Có gì mới trong v1.2.5

- **Nâng cấp Engine:** core đi kèm giờ là 1.6.0.
- **Sửa tương thích build:** trình phân tích dải WireGuard viết tay giờ do app sở
  hữu và không còn phụ thuộc helper nội bộ đã bị gỡ, nên engine native biên
  dịch sạch.
- **Giữ nguyên chữ ký:** version code là **9** và cấu hình ký hiện tại không đổi
  để cập nhật tại chỗ từ cùng repository.
- **Giữ nguyên tương thích UI:** các cài đặt giao thức, proxy, định tuyến, chẩn
  đoán và nâng cao hiện có vẫn còn.

**Phiên bản:** app 1.2.5, version code **9**.

## Có gì mới trong v1.2.4

- **Kill Switch và Strict Kill Switch**: khi rớt bất ngờ, một blackhole TUN chặn
  đứng vẫn dựng lên nên không traffic nào lọt ra ngoài VPN; chế độ Strict tiếp
  tục chặn ngay cả sau khi ngắt tay cho đến khi bạn tự gỡ.
- **Bảo vệ rò rỉ IPv6**: toggle mới, mặc định bật; default route IPv6 được giữ
  trong tunnel.
- **Tự kết nối lại thông minh với giới hạn lần thử**: số lần tự thử lại có thể
  cấu hình (3 đến 20) trước khi báo lỗi.
- **Chặn internet theo từng app**: cầu lọc userspace mới phân giải app sở hữu
  mỗi flow và chặn traffic của các app bị chặn; nó chỉ thay thế forwarder hev
  mặc định khi tính năng này bật, nên đường mặc định không bị động tới.
- **Cài đặt engine nâng cao**: dải Fragment Size/Delay, No Data Check, TLS
  Groups, Validate/Reconnect Secs, No Profile Retry và log level của core, tất
  cả được validate và chỉ ánh xạ tới các flag mà native core đi kèm thực sự hỗ
  trợ.
- **Các mục UI mới**: "Bảo mật & ổn định" và "Cài đặt engine nâng cao" trong
  panel Nâng cao, được bản địa hóa đầy đủ (tiếng Anh + tiếng Ba Tư) và lưu trong
  DataStore và profile codec.
- **Sửa lỗi "Chạy 1-2 phút rồi không mở được site nào"**: watchdog kết nối thăm
  dò tunnel end-to-end mỗi 30 giây và khởi động lại engine khi lỗi kéo dài, và
  các idle timeout của tunnel được nâng lên (TCP 60 s lên 5 phút, UDP lên 120 s).
- **Sửa tận gốc hiện tượng rớt định kỳ**: probe của watchdog giờ đa lần thử và
  đa mục tiêu, chỉ khởi động lại engine sau ba lần kiểm tra lỗi liên tiếp, nên
  những khựng mạng ngắn tự hồi phục không còn giết session khỏe và ép quét
  endpoint lại lâu.
- **Hàng thông tin ngang hàng desktop**: Protocol, Endpoint và Latency trực tiếp
  giờ nằm ngay dưới huy hiệu IP (port từ bản Windows); huy hiệu ping độc lập cũ
  bị gỡ.
- **Bảo mật**: một đợt kiểm toán bảo mật 0-100 mới được thực hiện và đạt
  **92/100** (báo cáo đầy đủ:
  [`docs/SECURITY_AUDIT_1.2.4.md`](docs/SECURITY_AUDIT_1.2.4.md)):
  | Hạng mục | Kết quả |
  | --- | --- |
  | 1. Khóa & bí mật | Bí mật Zero Trust được niêm phong trong Android Keystore (AES-GCM); không hardcode; chỉ truyền cho engine qua env |
  | 2. Mật mã học & giao thức | WireGuard và MASQUE/QUIC với TLS 1.3, ECH và phân mảnh ClientHello; không crypto tự chế |
  | 3. Rò rỉ dữ liệu | Định tuyến IPv4/IPv6 đầy đủ, DNS trong tunnel, Kill Switch; lối ra công cộng duy nhất là probe định vị địa lý của huy hiệu IP |
  | 4. Lưu trữ cục bộ | DataStore riêng, `allowBackup=false`, không Provider exported |
  | 5. Quyền & manifest | Quyền tối thiểu, không `QUERY_ALL_PACKAGES` hay `debuggable` |
  | 6. Log | Chỉ trong bộ nhớ; output engine chỉ tới Logcat ở bản debug |
  | 7. Chất lượng code & mạng | Cleartext bị từ chối toàn app, proxy bind 127.0.0.1, validate input; sửa self-DoS của watchdog |
- **Phiên bản**: app 1.2.4 (version code 8).

## Có gì mới trong v1.2.3

Đợt phát hành này nâng cấp engine đi kèm lên **core v1.5.0**, đưa mọi thứ mới mà
core thêm vào UI Android, đưa panel About ngang hàng với bản Windows, và phát
hành một đợt kiểm toán bảo mật mới.

### 🚀 Engine nâng cấp lên core v1.5.0 — và các patch của app được rebase đúng cách

- **Core giờ là v1.5.0.** Bản phát hành trước ghim `CORE_VERSION` ở `1.4`, nhưng
  mã nguồn thực tế vendored lại là upstream **v1.3.0** — nên logic merge-base tự
  động không có gì hợp lệ để so sánh và đã lặng lẽ đánh rơi các patch engine của
  app. Baseline đã được xác định, các patch được áp lại bằng **three-way
  merge** thật, và `native/aether/.upstream-baseline/` được sửa để lần nâng cấp
  tự động tiếp theo có merge base thật.
- **Hấp thụ refactor của upstream, giữ tính năng của app.** Core v1.5.0 biến các
  hằng endpoint-range thành hàm (`masque_cidrs_v4()`, `wg_prefixes_v4()`,
  `wg_seeds_v4()`) với thứ tự ưu tiên Zero-Trust. Cả hai xung đột merge được
  giải quyết bằng cách **chấp nhận thứ tự mới của upstream** trong khi giữ
  nguyên override dải thủ công của app. Các file đã merge giờ chỉ khác pure
  upstream ở đúng phần patch cộng thêm.

### 🆕 Mọi thứ mới trong core v1.5.0 giờ đã có trong UI

Core v1.5.0 không chỉ là tăng số phiên bản — nó thêm cả module mới
(`zerotrust.rs`, `routing.rs`, `apifront.rs`, `sysprofile.rs`). Các tùy chọn
người dùng đều đã được đấu nối vào **Cài đặt nâng cao**:

- **Zero Trust (WARP cho tổ chức).** Tham gia tổ chức Cloudflare Zero Trust thay
  vì WARP người dùng. Bốn phương thức enrolment: tắt, **service token** (client
  id + secret), **mã một lần qua e-mail**, và **enrolment token lấy trước**.
  Proxy **Gateway** của tổ chức là toggle opt-in riêng.
- **Quy tắc định tuyến.** Hai danh sách mới quyết định traffic đi đâu: **block**
  (không bao giờ ra mạng) và **direct** (lách qua tunnel). Cả hai chấp nhận đầy
  đủ ngữ pháp của engine — `example.com`, `full:`, `keyword:`, `regexp:`,
  `10.0.0.0/8`, `port:25`, `port:3000-3010` và `private`. Block được đánh giá
  trước, rồi đến direct, còn lại dùng tunnel.
- **DNS tùy biến trong tunnel.** Các resolver dùng *trong* tunnel giờ cấu hình
  được; để trống giữ mặc định của engine (1.1.1.1, 1.0.0.1).
- **Cải tiến nội bộ được hưởng miễn phí.** `sysprofile.rs` chỉnh concurrency
  quét và socket buffer theo hạng CPU/RAM của thiết bị, và `apifront.rs` trình
  diện fingerprint TLS legacy để hòa vào các mạng bị kiểm duyệt. Không cái nào
  cần cài đặt, và không cái nào làm yếu crypto của tunnel.

### ℹ️ Phiên bản engine giờ hiển thị trong About (ngang hàng bản Windows)

- Trang About của bản Windows hiển thị **phiên bản app và phiên bản core** cạnh
  nhau, để người dùng xác minh engine đi kèm còn mới. Panel About của Android
  giờ làm đúng vậy: hàng **"Phiên bản Engine (core)"** mới nằm dưới phiên bản
  app.
- Giá trị lấy từ `BuildConfig.CORE_VERSION`, được đóng dấu lúc build từ
  `native/aether/CORE_VERSION` — tức từ đúng core mà bước core-sync thực sự
  vendored cho *bản build đó*. Nó không bao giờ lệch khỏi engine thực sự phát
  hành.

### 🔐 Bảo mật

- **Một đợt kiểm toán bảo mật 0 đến 100 mới đã thực hiện cho bản này và đạt
  88/100.** Báo cáo đầy đủ tại
  [`docs/SECURITY_AUDIT_1.2.3.md`](docs/SECURITY_AUDIT_1.2.3.md).
- **Bí mật của tổ chức không bao giờ chạm vào command line.** Trên Android, app
  nào cũng đọc được `/proc/<pid>/cmdline` của tiến trình nó thấy được, nên
  secret service-token trong argv sẽ bị đọc quá rộng. Chỉ tên team không-bí-mật
  và flag `--gateway` đi dưới dạng argument; client id, client secret, enrolment
  token và e-mail được trao cho engine qua **môi trường (environment)** của nó,
  đúng nơi core đọc chúng.
- **Bí mật được niêm phong bằng khóa có hardware hậu thuẫn.** `SecretStore` mới
  mã hóa service-token secret và enrolment JWT bằng khóa **AES-256-GCM** không
  thể xuất, sinh trong **Android Keystore**, thay vì để trong file preferences
  DataStore plain nơi backup hay dump thiết bị root sẽ lộ. Các trường credential
  bị che trong UI.
- **Các tùy chọn tự do mới được validate trước khi thành argument.** Các mục DNS
  và quy tắc định tuyến được kiểm tra theo allow-list nghiêm, khử trùng lặp và
  giới hạn cứng (8 resolver, 256 rule), nên text dán vào chứa khoảng trắng hay
  ký tự đặc biệt shell không bao giờ tách thành argument engine phụ.
- **Toggle Gateway được ghi nhãn trung thực.** Mô tả của nó nêu rõ bật lên sẽ
  thêm một hop trong tunnel và cho phép tổ chức log hoạt động duyệt của bạn — nó
  tắt trừ khi bạn cần.

### 📦 Phiên bản

- Phiên bản app **1.2.3**, version code **7** (code theo ABI ở dải 7000). Cùng
  chứng chỉ ký với 1.2.2, nên **người dùng 1.2.2 cài trực tiếp đè lên app hiện
  tại và giữ nguyên mọi cài đặt** — không cần gỡ.

---

## Có gì mới trong v1.2.2

Đợt phát hành này đưa việc bảo trì engine vào pipeline build, **gỡ bỏ trình cập
nhật trong app**, gỡ bỏ bộ chọn quốc gia (nó không bao giờ thực sự chọn được
quốc gia) và trả việc chọn endpoint lại cho engine, đồng thời thực hiện một đợt
rà soát hiệu năng, tương thích và bảo mật rộng trên code 1.2.1.

### 🔄 Build giờ sở hữu phiên bản engine (tự động nâng cấp core CI/CD)

- **Đồng bộ core tự động mỗi lần build.** Bước `scripts/sync-core.sh` mới chạy
  trong GitHub Actions trước khi biên dịch native. Nó truy vấn [repository
  Aether Core chính thức](https://github.com/CluvexStudio/Aether), so sánh bản
  upstream mới nhất với phiên bản ghim trong `native/aether/CORE_VERSION`, và
  tự động nâng cấp engine vendored khi có bản mới hơn. Engine được ghim ở
  **v1.4** làm tham chiếu ổn định hiện tại.
- **Các patch engine của app được rebase, không copy.** Code quét dải tùy biến
  mà app dựa vào (`prober.rs`, `wg_prober.rs`) được mang qua đợt nâng cấp bằng
  **three-way merge** thật với baseline upstream nguyên vẹn cache trong
  `native/aether/.upstream-baseline/`. Thay đổi API của upstream và phần thêm
  của app vì thế kết hợp đúng, hệt như rebase. Nếu một file không merge được,
  build giữ phiên bản **pure upstream** (đảm bảo biên dịch được) và đánh dấu run
  để review thủ công — không bao giờ ép bản copy cũ làm hỏng biên dịch.
- **Nâng cấp core không bao giờ làm hỏng release.** Engine trước được snapshot
  trước mỗi lần nâng cấp. Nếu core mới sync không biên dịch được vì bất kỳ lý do
  gì, CI tự động rollback về engine mà repository đã phát hành, build lại và
  tiếp tục, có ghi chú trên run. `CORE_VERSION` và changelog mới chỉ được commit
  **sau khi engine thực sự build xong**, nên repository không bao giờ ghi nhận
  một đợt nâng cấp không hoạt động.
- **Thiết kế an toàn khi lỗi.** Nếu GitHub không thể truy cập, hoặc layout
  upstream bất ngờ, build giữ core vendored và tiếp tục — sự cố mạng không bao
  giờ phá hỏng release. Việc sync chỉ tiến **lên**; không bao giờ hạ cấp.
- **Được regression-test offline.** `scripts/test-core-sync.sh` phát lại toàn bộ
  đường nâng cấp trên repository cục bộ (upstream thêm field struct và argument
  hàm, viết lại không merge được, và thử hạ cấp) để logic sync được xác minh mà
  không chạm mạng.
- **Toolchain build được ghim.** Gradle cố định ở **8.9** khớp AGP 8.7.2 thay vì
  chạy theo phiên bản image CI runner vô tình phát hành, và bản build release
  có heap 4 GB tường minh — toolchain không còn trôi ngầm dưới dự án.
- **Các khả năng engine mới được phơi ra, không giấu.** Sau nâng cấp, script
  quét core mới tìm các khả năng command-line mà UI chưa phơi và báo cáo trong
  build log và changelog này, nên không tính năng engine nào phát hành mà thiếu
  quyết định UI tương ứng.
- **Build tự tài liệu hóa.** Sau nâng cấp thành công, CI sửa README này (và bản
  tiếng Ba Tư), ghi phiên bản core mới vào mục này, và commit thay đổi về
  nhánh. Phiên bản engine phát hành và tài liệu không còn lệch nhau.
- **Phiên bản engine hiển thị trong app.** Trường build `CORE_VERSION` thay thế
  hằng repository cũ và hiển thị trong panel About.

### 🗑️ Hệ thống cập nhật trong app bị gỡ bỏ

> **Vì bảo mật của người dùng, vì minh bạch hoàn toàn, và để đảm bảo tính xác
> thực của code họ nhận được, khả năng cập nhật trực tiếp trong app đã bị gỡ
> bỏ. Từ nay, mọi bản cập nhật chỉ có trên trang GitHub Release chính thức của
> dự án, được ký chính thức — ngăn mọi lượt tải không mong muốn từ nguồn không
> rõ.**

Cụ thể, đợt phát hành này xóa:

- `core/UpdateChecker.kt` — module truy vấn GitHub và tải APK ngầm.
- `ui/components/UpdatePrompt.kt` — thẻ "có bản cập nhật / cài ngay" trong app.
- Quyền `REQUEST_INSTALL_PACKAGES` — **app không còn cài đặt được bất cứ thứ
  gì**. Đây là quyền rủi ro cao nhất trong 1.2.1.
- Component `FileProvider` và `res/xml/file_paths.xml` — chúng chỉ tồn tại để
  đưa APK đã tải cho system installer.
- Sáu chuỗi `update_*` trong cả hai locale.

App giờ mang một con trỏ `RELEASES_URL` chỉ đọc tới trang releases chính thức.
**Không còn đường code nào trong Aether có thể tải hay thực thi binary mới.**

### 🌐 Không danh sách server/vị trí — engine tự chọn endpoint

Aether không có danh sách quốc gia và không có danh sách server, và chưa bao giờ
thực sự có. Nó kết nối bạn tới **mạng WARP của Cloudflare**, có địa chỉ
**anycast**: cùng một IP được công bố từ mọi datacenter Cloudflare cùng lúc,
nên datacenter trả lời do định tuyến của nhà mạng bạn quyết định — không phải
app. Menu quốc gia trong app cùng lắm chỉ là nhãn; nó không thể chuyển bạn sang
quốc gia đó. Đó là lý do bộ chọn (và cả catalogue của nó) bị gỡ trong đợt này.

- **Engine chọn endpoint.** Nó quét các dải WARP built-in, đo đạc và kết nối tới
  edge nào trả lời tốt nhất trên mạng của bạn tại thời điểm đó.
- **Smart Auto vẫn làm đúng việc thật của nó:** fingerprint DPI của mạng và
  chọn thang giao thức / obfuscation.
- **Cài đặt của bạn vẫn thắng.** Peer gõ tay (`ip:port`) hay dải quét tùy biến
  trong Settings vẫn ghim lần quét đúng như trước, và kết nối lại nhanh hoạt
  động bình thường.
- **Không lọc IP ép buộc.** Chặn exit theo quốc gia cũng không đáng tin — cổng
  thử nghiệm "không bao giờ Iran" từ chối gần như mọi edge trên mạng Iran thực
  và khiến app thử lại đến bỏ cuộc, nên đã bị gỡ. Độ tin cậy kết nối là trên
  hết.

### ⚡ Ổn định, tài nguyên và tương thích

- **Sửa rò rỉ bộ nhớ trong log chẩn đoán.** Log từng phình một list bất biến
  không giới hạn suốt session — trên kết nối dài nó ngốn RAM đều đều và khiến
  mỗi dòng mới đắt dần. Giờ nó là ring buffer 800 dòng có giới hạn, cập nhật UI
  bị throttle còn ~5 lần/giây thay vì một recomposition mỗi dòng, và ghi đĩa
  được gom theo batch trên background thread. Bản thân file log bị giới hạn 512
  KiB có xoay vòng.
- **Cắt mạnh overhead CPU lúc idle.** Supervisor VPN từng thức dậy mỗi hai giây
  suốt session chỉ để hỏi engine còn sống không. Giờ nó **block** trên chính
  tiến trình engine và chỉ thức khi tiến trình thực sự thoát — nên phát hiện
  crash *nhanh hơn* trước trong khi CPU được ở deep idle.
- **Hết busy-wait sau khi kết nối.** Vòng lặp polling 100 giây, 250 mili-giây
  chờ tra IP được thay bằng suspending wait đúng nghĩa.
- **Ít thread hơn khi kết nối.** Các probe định vị địa lý giờ dùng chung một
  thread pool ưu tiên thấp thay vì spawn thread mới cho mỗi provider mỗi lần
  thử, và thăm dò cổng backoff thích ứng thay vì poll cố định.
- **Xung đột v2rayNG được giải quyết.** Cầu chia sẻ LAN của Aether dùng cổng
  **10808/10809** — đúng cổng SOCKS và HTTP mặc định của v2rayNG. Khi cả hai
  app cùng cài, app khởi động sau không bind được, hoặc traffic bị lặng lẽ giao
  cho core của tool kia. Aether giờ dùng **10810/10811**, và còn phát hiện các
  app kề đã biết (v2rayNG, Clash, Psiphon, Privoxy) trước khi bind, nêu tên app
  chịu trách nhiệm trong thông báo lỗi thay vì lỗi "port busy" chung chung.
- **Vấn đề chữ ký / cài đè được sửa tận gốc.** Android từ chối cập nhật khi APK
  mới ký bằng chứng chỉ khác, đó là thứ ép người dùng 1.2.1 phải gỡ cài đặt và
  mất cài đặt. CI giờ trích SHA-256 fingerprint của người ký từ mọi APK release
  và so với giá trị ghim trong `.github/expected-signer.txt`; nếu chứng chỉ
  từng thay đổi, **build thất bại thay vì phát hành APK người dùng không cài
  được**. Kết hợp với keystore ổn định được lưu, **người dùng 1.2.1 có thể cài
  1.2.2 đè trực tiếp lên app hiện tại và giữ mọi cài đặt** — không cần gỡ.
  Version code chuyển từ 5 lên 6 (code đơn điệu theo ABI ở dải 6000).
- **Đổi giao thức không còn treo app.** Ngắt kết nối rồi kết nối bằng giao thức
  khác có cảm giác "mãi mới bắt đầu", trên mọi giao thức trừ Smart. Hai bug thật
  gây ra. Thứ nhất, dừng engine là fire-and-forget: app gửi tín hiệu terminate
  lịch sự cho tiến trình rồi lập tức đi tiếp, nên engine cũ thường vẫn còn sống
  và vẫn giữ cổng SOCKS5 cục bộ `127.0.0.1:1819` trong khi engine mới đã khởi
  động — lần thử mới hoặc không bind được hoặc tự xác minh với socket đang chết
  và phải timeout rồi thử lại. Engine giờ được chờ thật sự (và force-kill nếu
  không thoát), và lần thử mới chờ cổng cục bộ được thả trước khi bắt đầu. Thứ
  hai, một lần kết nối chạm vào trong lúc session trước vẫn đang dọn dẹp bị
  **lặng lẽ loại bỏ** ("a run is already active → return"), đúng triệu chứng
  "tôi nhấn kết nối mà một lúc không có gì xảy ra"; session mới giờ tiếp quản,
  join cái cũ, dọn nó và bắt đầu ngay.
- **MASQUE (và mọi giao thức chọn tay) có cơ hội thứ hai thật sự.** Giao thức
  chọn tay từng chỉ có đúng **một** lần thử với toàn bộ ngân sách quét của chế
  độ quét đã chọn — tới 150 s ở Balanced, 300 s ở Thorough — và không fallback,
  trong khi Smart đi thang các lần thử ngắn, cứng cáp. Trên mạng bóp UDP/QUIC,
  nghĩa là nhìn chằm chằm "Đang kết nối" hàng phút rồi thất bại. Giờ giao thức
  đã chọn chạy lượt đầu có giới hạn đúng như cấu hình, và nếu thất bại,
  **cùng** giao thức đó được thử lại với tăng cường chống DPI (bật obfuscation,
  cộng HTTP/2, phân mảnh TLS và ECH cho MASQUE) trên toàn bộ ngân sách. Giao
  thức bạn chọn không bao giờ bị đổi sang cái khác.
- **Ngắt kết nối lại tức thì (sửa tận gốc hiện tượng đơ 30–50 s).** Chạm ngắt
  kết nối có thể để nút ở "Disconnecting…" tới một phút trên mọi giao thức.
  Nguyên nhân gốc: supervisor của service park trên tiến trình engine bằng
  `Process.waitFor`, một lời gọi Java **blocking** — và hủy coroutine không thể
  ngắt một lời gọi blocking. Việc dọn dẹp yêu cầu session dừng rồi *chờ nó xong
  mới kill*, nên nó ngồi trong wait đó đến hết cả cửa sổ 60 giây (log cho thấy
  engine bị dừng đúng 60.0 s sau khi kết nối, không phải lúc nút được chạm). Hai
  thay đổi sửa triệt để: việc chờ engine giờ có thể ngắt, nên hủy bỏ nó trong
  mili-giây, và thứ tự dọn dẹp bị đảo ngược — hủy, kill native ngay lập tức, lật
  UI về idle, và chỉ *sau đó* mới reap coroutine đã xong ngoài critical path.
  Kết nối lại bằng giao thức khác theo cùng thứ tự, nên không còn thừa hưởng
  lần chờ của session cũ. Dừng engine cũng leo thang thành hard kill sau 250 ms
  thay vì chờ hàng giây.
- **Pipeline release không còn bị phá bởi file tồn đọng từ 1.2.1.** Copy 1.2.2
  đè lên repository 1.2.1 hiện có ghi đè các file đã đổi nhưng không xóa được
  các file mà 1.2.2 *đã gỡ* (trình cập nhật trong app, bộ chọn vị trí, chính
  sách thoát ép). Những file mồ côi đó vẫn tham chiếu symbol và string resource
  không còn tồn tại, nên `compileReleaseKotlin` lỗi với `Unresolved reference
  'GITHUB_REPO'`, `'update_available'` v.v. — đúng lý do cùng mã nguồn build
  tốt trong repository mới và lỗi trong repository thật. Build giờ tự dọn chúng:
  `scripts/purge-stale-sources.sh` xóa mọi đường dẫn liệt kê trong
  `.github/removed-sources.txt` trước khi biên dịch, commit việc dọn về nhánh,
  và còn hard-fail trong một giây với thông báo file+dòng chính xác nếu *bất
  kỳ* mã Kotlin nào còn tham chiếu string resource không tồn tại.
- **Chứng chỉ ký của 1.2.1 giờ được chính build bảo vệ.** Android chỉ cài bản
  cập nhật khi APK cũ và mới mang cùng chữ ký, nên khóa không bao giờ được đổi.
  Hai chốt chặn được thêm: build từ chối tạo keystore CI mới trong repository
  đã từng phát hành bằng keystore (nó dừng với lỗi tường minh "khôi phục
  `.github/ci-keystore.jks.b64`" thay vì lặng lẽ tạo APK không cài được), và
  fingerprint người ký giờ được ghim và ép buộc cả ở chế độ CI-key, theo từng
  repository (`.github/expected-signer-ci.txt`). Nếu chứng chỉ từng khác với
  chứng chỉ bản release trước phát hành, build thất bại thay vì phát hành.
  Không có gì rò rỉ giữa các repository: repository thử nghiệm ghim giá trị của
  riêng nó.
- **Animation aurora biến mất; nền giờ là màu phẳng.** Ba gradient tròn lớn từng
  được composite full-screen sau mọi màn hình suốt thời gian app mở. Ngay cả
  sau khi tốc độ vẽ lại bị giới hạn, nó vẫn tốn GPU và CPU thật trên mọi frame
  budget mà UI cần. Nền giờ là một fill tĩnh không bao giờ invalidate — không
  animation nào chạy sau UI nữa, nên menu, sheet và màn hình kết nối có toàn bộ
  frame budget.
- **Menu chính không còn chạy khi đang đóng.** Navigation drawer của Android
  compose nội dung ngay cả khi drawer đóng, nên các thẻ diagnostics, sharing,
  advanced và about luôn sống, recompose mỗi lần cài đặt đổi và mỗi dòng log
  engine sau một panel không ai nhìn. Giờ chúng chỉ được build khi drawer thực
  sự mở.
- **Log chẩn đoán chỉ subscribe khi đang mở.** Trong lúc quét, engine xả hàng
  trăm dòng log; mỗi dòng từng recompose cả thẻ diagnostics — và cả drawer quanh
  nó — ngay cả khi console log thu gọn. Giờ chỉ console đang mở mới lắng nghe.
- **Sheet Nâng cao mở tức thì.** ~40 control của nó từng được layout trong cùng
  frame sheet bắt đầu animation trượt vào, làm giật rõ lúc mở. Sheet giờ
  animation vào trước rồi điền nội dung ngay sau.

### 🔒 Kiểm toán bảo mật đầy đủ

**Dự án này đã trải qua kiểm toán bảo mật 100% từng dòng cho v1.2.2, và các lỗ
hổng bảo mật nghiêm trọng được phát hiện đã được khắc phục theo tiêu chuẩn kiểm
toán app di động.** Báo cáo đầy đủ được công bố tại
[`docs/SECURITY_AUDIT_1.2.2.md`](docs/SECURITY_AUDIT_1.2.2.md) và bao phủ mọi
lĩnh vực bắt buộc:

| Hạng mục | Kết quả |
|---|---|
| Bí mật & khóa hardcode | **Sạch.** Không API key, token, mật khẩu hay private key trong app; mọi key material của tunnel được sinh lúc runtime bên trong engine. |
| Mật mã học & giao thức | **Sạch.** Không primitive yếu hay lỗi thời, không `TrustManager`/`HostnameVerifier` tùy biến, không đường downgrade; bảo mật tunnel dựa trên xác thực khóa Noise/QUIC + TLS 1.3. |
| Phơi bày MitM | **Không khai thác được.** Request plaintext duy nhất là probe IP-echo ẩn danh không mang dữ liệu người dùng; giả mạo nó cùng lắm hiện sai cờ. |
| Rò rỉ DNS / IPv6 / IP thật | **Không có.** Cả hai default route IPv4 và IPv6 đều bị tunnel chụp (rò rỉ IPv6 kinh điển đã đóng), và DNS-qua-tunnel được xác minh chủ động trước khi UI báo "Đã kết nối". |
| Lách traffic | **Chỉ do người dùng điều khiển.** Split tunneling mặc định tắt; không gì rời tunnel trừ khi bạn cấu hình. |
| Lưu trữ cục bộ | **Riêng của app.** Chỉ lưu preferences — không credential, không key — và file log giờ có giới hạn và xoay vòng. |
| Quyền & manifest | **Tối thiểu.** Đã gỡ `REQUEST_INSTALL_PACKAGES`; không vị trí/danh bạ/bộ nhớ/`QUERY_ALL_PACKAGES`; `android:debuggable` vắng mặt ở bản release; `allowBackup` bị giới hạn bởi backup rules loại trừ cài đặt và log; chỉ launcher activity được exported. |
| Ghi log | **An toàn.** Output Logcat được biên dịch loại khỏi bản release, và không key, truy vấn DNS, hostname hay packet payload nào bị ghi. |
| Dependency & cấu hình mạng | **Được tăng cường.** Không SDK quảng cáo, analytics hay báo crash; các core native được build từ mã nguồn trong CI; traffic cleartext bị từ chối mặc định và CA do người dùng cài không được tin tưởng. |

Một rủi ro được chấp nhận và công khai thay vì giấu: keystore CI dự phòng trong
repository cố ý công khai — nó đảm bảo tính liên tục của cập nhật, không đảm
bảo tính xác thực. Luôn tải từ trang Releases chính thức và xác minh fingerprint
của người ký.

<details>
<summary>🇻🇳 Mục này bằng tiếng Việt (kiểm toán bảo mật đầy đủ)</summary>

#### 🔒 Kiểm toán bảo mật đầy đủ

**Dự án này đã trải qua kiểm toán bảo mật 100% từng dòng cho v1.2.2, và các lỗ
hổng bảo mật nghiêm trọng được phát hiện đã được khắc phục theo tiêu chuẩn kiểm
toán app di động.** Báo cáo đầy đủ được công bố tại
[`docs/SECURITY_AUDIT_1.2.2.md`](docs/SECURITY_AUDIT_1.2.2.md) và bao phủ mọi
lĩnh vực bắt buộc:

| Hạng mục | Kết quả |
|---|---|
| Bí mật & khóa hardcode | **Sạch.** Không API key, token, mật khẩu hay private key trong app; mọi key material của tunnel được sinh lúc runtime bên trong engine. |
| Mật mã học & giao thức | **Sạch.** Không primitive yếu hay lỗi thời, không `TrustManager`/`HostnameVerifier` tùy biến, không đường downgrade; bảo mật tunnel dựa trên xác thực khóa Noise/QUIC + TLS 1.3. |
| Phơi bày MitM | **Không khai thác được.** Request plaintext duy nhất là probe IP-echo ẩn danh không mang dữ liệu người dùng; giả mạo nó cùng lắm hiện sai cờ. |
| Rò rỉ DNS / IPv6 / IP thật | **Không có.** Cả hai default route IPv4 và IPv6 đều bị tunnel chụp (rò rỉ IPv6 kinh điển đã đóng), và DNS-qua-tunnel được xác minh chủ động trước khi UI báo "Đã kết nối". |
| Lách traffic | **Chỉ do người dùng điều khiển.** Split tunneling mặc định tắt; không gì rời tunnel trừ khi bạn cấu hình. |
| Lưu trữ cục bộ | **Riêng của app.** Chỉ lưu preferences — không credential, không key — và file log giờ có giới hạn và xoay vòng. |
| Quyền & manifest | **Tối thiểu.** Đã gỡ `REQUEST_INSTALL_PACKAGES`; không vị trí/danh bạ/bộ nhớ/`QUERY_ALL_PACKAGES`; `android:debuggable` vắng mặt ở bản release; `allowBackup` bị giới hạn bởi backup rules loại trừ cài đặt và log; chỉ launcher activity được exported. |
| Ghi log | **An toàn.** Output Logcat được biên dịch loại khỏi bản release, và không key, truy vấn DNS, hostname hay packet payload nào bị ghi. |
| Dependency & cấu hình mạng | **Được tăng cường.** Không SDK quảng cáo, analytics hay báo crash; các core native được build từ mã nguồn trong CI; traffic cleartext bị từ chối mặc định và CA do người dùng cài không được tin tưởng. |

Một rủi ro được chấp nhận và công khai thay vì giấu: keystore CI dự phòng trong
repository cố ý công khai — nó đảm bảo tính liên tục của cập nhật, không đảm
bảo tính xác thực. Luôn tải từ trang Releases chính thức và xác minh fingerprint
của người ký.

</details>

---

## Có gì mới trong v1.2.1

Đợt phát hành này giúp kết nối **nhanh hơn và trung thực hơn**, sửa lỗi text
locale tiếng Ba Tư, và tăng cường bảo mật.

- **"Đã kết nối" giờ nghĩa là đã kết nối** — app ở trạng thái mới
  **"Verifying connection…"** cho đến khi cả bốn kiểm tra sức khỏe (port,
  handshake, internet, IP) pass. Không còn bị báo đã kết nối trong khi thực ra
  chưa tải được gì.
- **Sửa chế độ Auto & giới thiệu engine thực sự thông minh (Smart Auto)** —
  Trước đây, chế độ Auto không gửi flag giao thức nào cho engine, mù quáng dựa
  vào mặc định của engine và thất bại trên mạng bị hạn chế. Module **Smart
  Auto** mới phân tích mạng như một kỹ sư trước khi khởi động:
  - **Khám phá DPI mạng (trước khi chạy engine):** Gửi các probe song song, đa
    giây trên đường đi thực của nhà mạng để đánh giá sức khỏe UDP (truy vấn DNS
    trực tiếp tới `1.1.1.1` và `8.8.8.8`), test SNI-DPI (bắt tay TLS đầy đủ với
    SNI tới Cloudflare), đo độ trễ TCP cho từng dải IP WARP, và phát hiện chi
    tiết nhà mạng (tên, mã quốc gia, loại mạng) mà không cần thêm quyền nào.
  - **Phân loại mạng:** Xếp mạng vào một trong bốn lớp: Open, SNI Filtering,
    UDP Throttling, hoặc Hostile.
  - **Thang chiến lược:** Sinh danh sách ưu tiên "Giao thức + Obfuscation
    (Noize) + Fragment/ECH + Dải IP thành công" cho mỗi lớp mạng, từ khả năng
    thành công cao nhất đến thấp nhất, cộng một lần thử fallback cuối.
  - **Kết nối từng bước:** Thử từng chiến lược tuần tự, xác minh bằng kiểm tra
    sức khỏe 4 bước. Chiến lược đầu tiên pass được chọn và khóa cho các lần tự
    kết nối lại.
  - **Lưu ý chính:** Chọn dải đang hoạt động đảm bảo các lần thử nhanh;
    obfuscation nhẹ tự áp dụng trên mạng di động Iran; endpoint/dải tùy biến do
    người dùng định nghĩa không bao giờ bị ghi đè; và mọi quyết định probe đều
    được log minh bạch. Trạng thái UI mới **"Analyzing network (Smart
    connect)…"** cũng được thêm.
- **IP & cờ hiện nhanh hơn nhiều** — ba dịch vụ tra IP giờ được truy vấn **song
  song** (dịch vụ nhanh nhất trên mạng *của bạn* thắng) thay vì từng cái một,
  các self-test chạy đồng thời, và độ trễ thử lại ngắn hơn. Điều này đặc biệt
  giúp các mạng nơi DPI làm chậm một số provider.
- **Sửa chữ số bị xáo trộn** khi gõ trong trường dải IP tùy biến và endpoint thủ
  công (và mọi nơi `ip:port` hiển thị) khi ngôn ngữ điện thoại là tiếng Ba Tư —
  vấn đề text phải-sang-trái (BiDi), đã sửa tận gốc.
- **Nút Reset mới** ở cuối cài đặt nâng cao — một chạm khôi phục mọi cài đặt về
  mặc định.
- **Tăng cường bảo mật** (báo cáo kiểm toán đầy đủ trong
  `docs/SECURITY_AUDIT.md`): xác minh hostname TLS trên các probe built-in
  (chặn man-in-the-middle), output engine không còn mirror ra Logcat ở bản
  release, HTTP cleartext bị từ chối toàn app, quy tắc backup chặt hơn.
- **Cập nhật trong app (kiểu Telegram) — beta** — khi phiên bản mới được phát
  hành trên GitHub Releases, chính app hiển thị banner "Update" trên màn hình
  chính; một chạm tải đúng APK cho thiết bị bạn và mở trình cài đặt. Không cần
  vào GitHub. **Tính năng này hiện đang beta (thử nghiệm)** và vẫn đang được ổn
  định.
- **Ký release đúng cách — beta** — mọi bản build được ký bằng một khóa ổn định
  (CI keystore của repo, hoặc khóa của bạn qua `keystore.properties` / repo
  Secrets; xem `docs/SIGNING.md`), nên các bản cập nhật cứ cài đè lên không cần
  gỡ. **Cơ chế ký cũng đang beta (thử nghiệm)** trong lúc được kiểm chứng trên
  các thiết bị.

## Có gì mới trong v1.2.0

Đợt phát hành này phát hành đầy đủ bộ tính năng nâng cao và giúp cập nhật tại
chỗ đáng tin cậy.

- **Obfuscation chống DPI kiểu Amnezia (Noize)** — Off / Light / Firewall /
  Balanced / GFW / Aggressive, để đánh bại fingerprint giao thức trên mạng bị
  lọc mạnh.
- **Chế độ quét "Ironclad" mới** — tìm endpoint bền bỉ nhất, cho các mạng khó
  nhất (thêm vào Turbo / Balanced / Thorough / Stealth).
- **Chọn endpoint** — Auto (quét các dải built-in), peer thủ công (ghim một
  `ip:port`), hoặc **dải tùy biến** (gõ dải IP của riêng bạn và engine quét
  *đúng* những dải đó, ví dụ `8.6.112.x`, `188.114.96.0/24`).
- **WireGuard keepalive**, **MTU chỉnh được** (mặc định 1280, tốt nhất cho mobile
  Iran / DPI mạnh), **phân mảnh TLS ClientHello**, và **Encrypted Client Hello
  (ECH)**.
- **Chế độ Proxy** — chạy engine + local SOCKS5/HTTP proxy *mà không* chụp toàn
  bộ thiết bị qua VPN hệ thống.
- **Split tunneling theo app** — chọn chính xác app nào dùng (hoặc bỏ qua)
  tunnel, kèm bộ chọn app built-in.
- **Các bản sửa:** readout "IP của bạn" đúng (hết IPv6 di động sai) và số liệu
  traffic upload/download đúng.
- **Cập nhật tại chỗ đáng tin** — khóa ký lưu trong repo của bạn, nên miễn bạn
  cứ build trong **cùng** repository, bản mới cài đè lên bản cũ không cần gỡ.
  Xem [Cập nhật & chữ ký app](#cập-nhật--chữ-ký-app).
- **Sửa sheet cài đặt nâng cao** — giờ cuộn mượt tới control cuối không bị cắt
  sau thanh điều hướng.
- **Sửa chế độ VPN toàn thiết bị** — CI giờ đóng gói và xác minh lõi TUN-to-SOCKS
  nhúng cùng mọi dependency runtime trong mọi APK.

## Có gì mới trong v1.1.0

- **Tile Cài đặt nhanh** — bật/tắt kết nối ngay từ notification shade mà không
  cần mở app. Thêm một lần: vuốt xuống → chạm nút bút chì/chỉnh sửa trong Quick
  Settings → kéo tile **Aether** vào các tile đang dùng.
- **Chia sẻ VPN với laptop hoặc điện thoại khác** — menu bên → **Share VPN**. Xem
  [Chia sẻ VPN](#chia-sẻ-vpn-với-laptop-hoặc-điện-thoại-khác) bên dưới.
- **Cài đặt nâng cao trên màn hình chính** — nút tune (góc trên-phải) mở mọi tùy
  chọn nâng cao trong bottom sheet.
- **Cập nhật tại chỗ** — các bản build giờ được ký bằng một khóa ổn định nên các
  bản cập nhật sau cài đè lên. Xem [Cập nhật & chữ ký app](#cập-nhật--chữ-ký-app).

## Đây là gì

Phiên bản Windows của Aether hoạt động như sau: app chạy **engine Aether**, mở
một **proxy SOCKS5 cục bộ tại `127.0.0.1:1819`**. Sau đó bạn dán địa chỉ đó vào
**v2rayNG** và để nó tunnel traffic của bạn.

**Aether Mobile loại bỏ hoàn toàn bước thứ hai.** App:

1. chạy cùng engine Aether nội bộ (mở SOCKS5 trên `127.0.0.1:1819`),
2. dựng Android **VpnService** (giao diện TUN của hệ thống),
3. dùng **lõi tunnel built-in** (`hev-socks5-tunnel`) chuyển mọi packet từ thiết
   bị TUN vào local SOCKS5 proxy đó.

Vậy toàn bộ chuỗi "engine + v2rayNG" giờ sống trong một app. Một chạm kết nối
mọi thứ.

```
  Your apps  ─►  Android VPN (TUN)  ─►  hev-socks5-tunnel  ─►  127.0.0.1:1819  ─►  Aether engine  ─►  Internet
                     (built in)          (built in, replaces v2rayNG)      (SOCKS5)          (built in)
```

## VPN này thực sự hoạt động thế nào — và dữ liệu của bạn đi đâu (giải thích đơn giản)

Nhiều người dùng hỏi: *"VPN miễn phí mà không có danh sách server… tôi đang
kết nối tới server của ai? Ai nhìn thấy dữ liệu của tôi?"* Câu hỏi chính đáng.
Đây là câu trả lời trung thực, đơn giản:

- **Không có server trung gian bí mật.** Aether không định tuyến bạn qua VPS
  của người lạ nào. Engine kết nối thiết bị của bạn tới **mạng WARP của
  Cloudflare** — cùng hạ tầng toàn cầu đằng sau app **1.1.1.1 / WARP** nổi tiếng
  được hàng triệu người dùng. Đó CHÍNH là "server đích": edge công cộng của
  Cloudflare, không phải thứ do chúng tôi vận hành.
- **Traffic của bạn được mã hóa trên điện thoại** bằng giao thức WireGuard hoặc
  MASQUE **trước khi rời thiết bị**, và chỉ được giải mã bên trong mạng
  Cloudflare trên đường tới website bạn thăm. Bất kỳ ai ở giữa (ISP, chủ Wi-Fi)
  chỉ thấy nhiễu đã mã hóa.
- **Nhà phát triển app không vận hành server nào và không nhận traffic nào của
  bạn.** Không analytics, không tài khoản, không log gửi về chúng tôi — không
  có gì để gửi, không nơi nào để gửi. Thứ engine thực sự thêm vào là *quét
  endpoint thông minh và obfuscation* để WARP tiếp tục hoạt động trên các mạng
  bị lọc mạnh.
- **Tin nhưng hãy xác minh:** toàn bộ app và engine đều mã nguồn mở ([repo
  này](https://github.com/QW-AI-Code) +
  [CluvexStudio/Aether](https://github.com/CluvexStudio/Aether)). Ai cũng có thể
  đọc code và xác nhận những điều trên; các APK được build công khai bởi GitHub
  Actions thẳng từ mã nguồn này.
- **Lưu ý trung thực:** như BẤT KỲ VPN nào, đơn vị vận hành mạng exit — ở đây là
  Cloudflare — về mặt kỹ thuật có thể thấy traffic đi qua nó (Cloudflare công
  bố [chính sách quyền riêng tư](https://www.cloudflare.com/application/privacy/)
  cho WARP). Các website bạn thăm qua HTTPS vẫn được mã hóa end-to-end. Nếu mô
  hình đe dọa của bạn không chấp nhận Cloudflare, không công cụ nào dựa trên
  WARP phù hợp với bạn.

**TL;DR:** dữ liệu của bạn đi: *điện thoại của bạn → (đã mã hóa) → Cloudflare
WARP → website*. Nhà phát triển không nằm trong đường đi đó.

## Điểm nổi bật

- **UI tối Material You** xây bằng Jetpack Compose. Dùng **dynamic color** theo
  hình nền trên Android 12+, và dùng bảng màu xanh navy sâu đẹp trên thiết bị cũ
  hơn.
- **Hai backend mạng:** **Aether**, và **`Aether → Psiphon`** ghép chuỗi cho
  exit IP nước ngoài thật (kèm bộ chọn quốc gia exit) khi địa chỉ Iran hay
  anycast của Cloudflare là thứ site từ chối.
- Nút kết nối phát sáng với dấu tick lớn khi đã thông, ánh sáng màu primary
  chạy quanh thẻ kết nối, và màn hình chính luôn vừa một màn hình không cần
  cuộn.
- Mọi tùy chọn như app desktop **và hơn nữa**: **protocol** (Smart / MASQUE /
  WireGuard / Gool), **scan mode** (Turbo / Balanced / Thorough / Stealth /
  **Ironclad**), **IP version** (v4 / v6 / cả hai), **quick reconnect** và
  **MASQUE trên HTTP/2** — cộng **obfuscation kiểu Amnezia (Noize)**,
  **endpoint thủ công / dải quét tùy biến**, **keepalive**, **MTU**, **phân mảnh
  TLS**, **ECH**, **chế độ proxy** và **split tunneling theo app**. Tất cả đều
  tới được từ menu bên lẫn thẳng từ **màn hình chính** (nút tune, góc
  trên-phải).
- **Tile Cài đặt nhanh** bật/tắt bằng một vuốt.
- **Chia sẻ VPN qua Wi-Fi/hotspot** — proxy HTTP + SOCKS5 built-in cho các thiết
  bị khác của bạn.
- Tự kết nối lại với backoff khi engine rớt.
- Song ngữ (tiếng Anh + tiếng Ba Tư) với RTL tự động.

## Tile Cài đặt nhanh (bật/tắt bằng một vuốt)

1. Vuốt xuống từ cạnh trên màn hình để mở Quick Settings.
2. Chạm nút **bút chì / chỉnh sửa** và kéo tile **Aether** vào các tile đang
   dùng (chỉ cần làm một lần).
3. Từ đó: **chạm tile để kết nối, chạm lần nữa để ngắt** — không cần mở app. Lần
   kết nối đầu tiên vẫn phải bắt đầu từ app một lần, để Android hiển thị hộp
   thoại xin quyền VPN chuẩn.

## Chia sẻ VPN với laptop hoặc điện thoại khác

Điện thoại của bạn có thể đóng vai trò **gateway** cho các thiết bị khác trên
cùng mạng Wi-Fi hoặc trên hotspot của điện thoại:

1. Kết nối VPN trong Aether.
2. Mở menu bên → **Share VPN** → bật **Share on this network**.
3. Panel hiển thị hai địa chỉ (chạm biểu tượng copy cạnh mỗi cái):
   - **HTTP proxy — `<your-phone-ip>:10809`** → nhập vào cài đặt **proxy hệ
     thống** của thiết bị kia (Windows: Settings → Network → Proxy → Manual;
     macOS: Wi-Fi → Details → Proxies → Web/Secure Web Proxy; Android/iOS:
     Wi-Fi → Modify network → Proxy → Manual).
   - **SOCKS5 proxy — `<your-phone-ip>:10808`** → cho các app/trình duyệt hỗ
     trợ SOCKS (ví dụ Firefox, Telegram).
4. Xong — traffic của thiết bị kia giờ đi qua tunnel trên điện thoại bạn.

> ⚠️ Khi chia sẻ đang bật, **bất kỳ ai trên mạng đó** đều có thể dùng proxy.
> Chỉ bật trên mạng bạn tin tưởng (hotspot của chính bạn là an toàn nhất). Chia
> sẻ tự dừng khi VPN ngắt kết nối.

## Cập nhật & chữ ký app (tôi có phải gỡ bản cũ không?)

Android chỉ cài bản cập nhật **đè lên** app cũ khi cả hai được ký bằng **cùng
một khóa**. Các bản build cũ của dự án dùng **khóa debug tạm thay đổi giữa các
bản build**, đó là lý do cập nhật từng đòi gỡ cài đặt hoàn toàn.

**Đã sửa trong v1.1.0.** CI giờ luôn ký bằng một khóa **ổn định**:

- Nếu bạn đặt Secrets keystore (bảng dưới), khóa của bạn được dùng — khuyến
  nghị.
- Nếu không, bản build đầu tiên sinh keystore CI và **commit nó vào repo**
  (`.github/ci-keystore.jks.b64`); mọi bản build sau tái dùng đúng khóa đó.

Điều đó có nghĩa với người dùng:

- **Từ v1.1.0 trở đi:** chỉ tải APK mới và cài — nó cập nhật tại chỗ, dữ liệu
  nguyên vẹn. Không bao giờ gỡ cài đặt nữa.
- **Nâng cấp từ v1.0.0 (hoặc cũ hơn):** cần một lần gỡ cài đặt cuối cùng, vì các
  bản build đó ký bằng khóa dùng một lần cũ. Sau đó, không bao giờ nữa.

> ♻️ **Mẹo cho người mới — giữ nguyên repo & giữ khóa.** Khóa ổn định nằm trong
> `.github/ci-keystore.jks.b64` bên trong repository *của bạn*. Khi upload đợt
> mã nguồn mới hơn, thêm/thay thế các file đã đổi trong **cùng** repo và **đừng
> xóa** file keystore đó — đó chính là thứ cho phép bản mới cài đè lên bản đã
> có trên điện thoại. Nếu bạn từng tạo repo hoàn toàn mới (hoặc file bị xóa),
> bản build tiếp theo tạo khóa mới, nên lần đó bạn cần một lần gỡ cài đặt duy
> nhất; sau đó lại ổn định vĩnh viễn.

> 🔒 **Lưu ý bảo mật:** keystore commit trong repo công khai là công khai — nó
> đảm bảo *khả năng cập nhật*, không đảm bảo *tính xác thực* (ai cũng có thể ký
> APK bằng nó). Nếu bạn phân phối app này nghiêm túc, hãy đặt Secrets dưới đây;
> chúng luôn được ưu tiên hơn keystore trong repo.

| Secret | Ý nghĩa |
|--------|---------|
| `KEYSTORE_BASE64` | Keystore `.jks` của bạn, mã hóa base64 |
| `KEYSTORE_PASSWORD` | Mật khẩu keystore |
| `KEY_ALIAS` | Alias của key |
| `KEY_PASSWORD` | Mật khẩu key |

Tạo base64 bằng: `base64 -w0 my-release-key.jks > keystore.txt`, rồi thêm
Secrets dưới Settings → Secrets and variables → Actions.

## Build trên GitHub (không cần cài đặt gì trên máy tính)

Bạn **không** cần Android Studio. GitHub Actions build mọi thứ cho bạn.

1. Tạo repository GitHub mới và upload nội dung thư mục này (hoặc push bằng
   git).
2. Vào tab **Actions** của repo và bật workflow nếu được nhắc.
3. Mỗi lần push lên `main` đều build app. Để có file release cài được, tạo
   version tag:
   ```bash
   git tag v1.2.0
   git push origin v1.2.0
   ```
4. Khi build xong, trang **Releases** sẽ có ba APK (xem dưới). Tiêu đề release
   gọn (`Aether v1.2.0`) và text "What's new" lấy từ `.github/release-notes.md`
   — cập nhật file đó cùng với phiên bản.

### Ba file APK

| File | Dành cho điện thoại nào |
|------|-----------------|
| `Aether-1.2.0-arm64-v8a.apk` | Hầu hết điện thoại hiện đại (ARM 64-bit) |
| `Aether-1.2.0-armeabi-v7a.apk` | Điện thoại 32-bit cũ / yếu |
| `Aether-1.2.0-universal.apk` | **Nếu không chắc, tải file này.** Chạy trên mọi điện thoại ARM |

## Các phần native được build thế nào

Upstream Aether **không** phát hành binary Android, nên CI build chúng từ mã
nguồn:

- `scripts/fetch-natives.sh` clone `hev-socks5-tunnel` (tunnel trong app) và mã
  nguồn engine Aether.
- `scripts/build-natives.sh` build hev bằng `ndk-build` thành
  `libhev-socks5-tunnel.so` và cross-compile engine Aether bằng `cargo-ndk`
  thành `libaether.so`, cho cả `arm64-v8a` và `armeabi-v7a`.
- Trước khi phát hành, CI kiểm tra mọi APK và từ chối release trừ khi cả hai
  native core thực sự có mặt cho mọi ABI đi kèm.

Bạn có thể ghim phiên bản qua biến môi trường: `HEV_REF`, `AETHER_REPO`,
`AETHER_REF`.

## Yêu cầu

- Android 8.0 (API 26) trở lên.
- App xin quyền VPN (đồng ý chuẩn của Android) và quyền thông báo (để hiển thị
  trạng thái liên tục).

## Ghi công & giấy phép

- [CluvexStudio/Aether](https://github.com/CluvexStudio/Aether) — engine.
- [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) — lõi
  tunnel.

Phát hành dưới **AGPL-3.0**. Xem [LICENSE](LICENSE).


## Gemini AI (1.2.9)

Mang theo Gemini key miễn phí của bạn và app có thêm bốn thứ. Tất cả đều tùy
chọn, tất cả đều tắt cho đến khi bạn nhập key, và không cái nào thay đổi cách
tunnel hoạt động.

**1. API key của bạn, model của bạn.** Dán key miễn phí từ
[Google AI Studio](https://aistudio.google.com/apikey) vào *Settings ->
Assistant* và nhấn **Test the API connection**. App hỏi key của bạn xem nó được
dùng những model nào và hiển thị đúng danh sách đó — key miễn phí, key có
billing và key từ khu vực model chưa ra mắt thấy các model khác nhau, nên không
có gì bị hard-code. Một model được chọn sẵn cho bạn; bạn có thể đổi.

Key được niêm phong bằng khóa AES-GCM có hardware hậu thuẫn từ Android Keystore
(`SecretStore`), không bao giờ ghi vào file cài đặt plain, không bao giờ ghi
vào log chẩn đoán, và được gửi tới Google trong **header** của request chứ không
phải query string. Reset cài đặt app không xóa nó.

**2. Lời khuyên cài đặt từ chính log của bạn.** Sau mỗi lần kết nối — hoặc theo
yêu cầu từ *Assistant -> Settings advisor* — Gemini đọc một đoạn trích đã
redacted của log phiên này, báo cáo việc kiểm tra của nhà mạng bạn dường như
đang làm gì với kết nối, và đề xuất các cài đặt phù hợp. Việc này **tối ưu
chính các tùy chọn của app**; không phải lời khuyên về hạ tầng của người khác.

Thứ bị redacted trước khi rời thiết bị: credential mọi hình thức (kể cả Gemini
key của bạn), và mọi IPv4 công cộng bị che tới /16. Các địa chỉ private và
loopback được giữ, vì `127.0.0.1:1819` là chuỗi chẩn đoán giá trị nhất trong cả
log. Không có gì được áp dụng cho đến khi bạn nhấn **Apply**, trừ khi bạn tự
bật tự động áp dụng.

**3. Biểu tượng AI cạnh mọi tùy chọn.** Chạm dấu nhỏ cạnh bất kỳ cài đặt nào và
bạn nhận được: nó là gì, để làm gì, và dùng thế nào — bằng ngôn ngữ của bạn.
Mỗi câu trả lời dựa trên mô tả thực tế của cài đặt đó, viết từ hành vi thật của
engine, nên model giải thích điều đúng thay vì đoán "Noize" hay "Scan mode" có
thể nghĩa là gì trong app VPN. Mô tả thực tế hiện ngay lập tức, có hay không có
key; câu trả lời của Gemini hiện bên dưới. Các câu trả lời được cache, nên mở
lại sheet không tốn quota. Tắt các biểu tượng trong *Assistant -> Behaviour*.

**4. Chat trong app.** *Assistant -> Chat with Gemini*, hoặc nút AI ở góc trên
màn hình chính. Hỏi bất cứ gì. Bảo nó đổi thứ gì đó và nó đề xuất patch bạn
duyệt bằng một chạm.

### AI được và không được đổi gì

Chỉ tinh chỉnh: protocol, scan mode, IP family, obfuscation, MTU, keepalive,
phân mảnh TLS và các dải của nó, ECH, MASQUE-trên-HTTP/2, quick reconnect, DNS
trong tunnel, domain sniffing, TLS groups, thời gian validate/reconnect, giới
hạn kết nối lại, kill switch, bảo vệ rò rỉ IPv6, cấp lại identity, quốc gia exit
và log level của core.

Nó **không bao giờ** được đổi: backend mạng, upstream proxy, danh sách định
tuyến block và direct, endpoint thủ công đã ghim hay dải quét, chế độ proxy,
split tunneling, các app bị chặn, chia sẻ qua LAN, hay bất kỳ credential Zero
Trust nào. Những thứ đó quyết định *traffic nào được bảo vệ và đi đâu*, và
allow-list ép buộc điều này nằm trong code (`ai/AiPatch.kt`), không phải trong
wording của prompt — thay đổi mà model không diễn đạt được là thay đổi nó không
thể thực hiện.

Các thay đổi được áp dụng được trao cho engine khi nó khởi động, nên có hiệu
lực ở **lần kết nối tiếp theo**. App nói rõ điều đó mỗi lần.

### Vì sao AI chỉ hoạt động khi đã kết nối, trong `Aether -> Psiphon`

Không phải chính sách. Hai sự thật:

- `AetherVpnService` gọi `addDisallowedApplication` trên chính package của app,
  nên các socket của Aether cố ý lách qua tunnel nó dựng. Một HTTP client
  thường sẽ đi ra trên mạng của nhà mạng, ở dạng rõ, và thất bại — đồng thời
  báo cho nhà mạng đó rằng thiết bị này vừa thử tới một endpoint AI bị chặn. Nên
  mọi request AI được quay số qua chính local SOCKS5 proxy của tunnel
  (`ai/GeminiHttp.kt`), với đích gửi dưới dạng **domain** để exit phân giải, và
  TLS kết thúc trên thiết bị với xác minh hostname được ép buộc. Khi tunnel sập
  thì không có đường nào cả.
- Các endpoint AI của Google từ chối hoặc thách thức các địa chỉ exit WARP của
  Cloudflare — đúng triệu chứng mà bản 1.2.8 phát hành release notes về. Chế độ
  ghép chuỗi exit qua địa chỉ Psiphon, mà họ chấp nhận.

Khi thiếu một trong hai điều kiện, mọi bề mặt AI đều nêu rõ thiếu cái nào và
đưa nút sửa nó.

### Không thêm dependency nào

JSON là `org.json` từ nền tảng Android; HTTP, TLS và SOCKS5 viết tay trên
`java.net`, cùng cách `core/NetProbe.kt` đã thăm dò tunnel. APK không thêm thư
viện bên thứ ba nào.

---

*Đây là bản phân phối tiếng Việt (Vietnamese distribution) của Aether Mobile,
dựa trên mã nguồn gốc tại https://github.com/QW-AI-Code/Aether.*
