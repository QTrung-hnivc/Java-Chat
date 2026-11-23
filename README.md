# Java Chat

Ứng dụng chat nhiều người trong mạng LAN, viết bằng **Java Swing thuần**, không dùng
thư viện ngoài. Một máy chạy `ChatServer`, các máy còn lại chạy `ChatClient` và kết nối
tới địa chỉ IP mà server hiển thị.

## Tính năng

- Chat nhóm nhiều người trong cùng một phòng, tên hiển thị tuỳ chọn.
- Giao diện bong bóng thoáng, tin của mình bên phải, người khác bên trái.
- Danh sách người đang online, cập nhật ngay khi có người vào/rời phòng.
- Thông báo hệ thống khi có người tham gia, rời phòng, hoặc mất kết nối.
- Hiển thị "ai đó đang nhập…".
- Tin nhắn nhiều dòng (`Shift + Enter`), `Enter` để gửi.
- Lưu 60 tin gần nhất ở server, người vào sau vẫn đọc được.
- Tự bỏ dấu tiếng Việt? Không — giữ nguyên dấu, UTF-8 toàn tuyến.
- Tự nhớ tên/IP/cổng lần kết nối trước (`%USERPROFILE%\.javachat_last`).
- Server có thể chạy không cần cửa sổ: `java ChatServer --console 5050`.

## Yêu cầu

- JDK 17 trở lên (đã kiểm thử với JDK 21).
- Hai máy trong cùng mạng LAN, hoặc chạy cả hai trên một máy để thử.

## Chạy thử trên một máy

Mở **hai** cửa sổ dòng lệnh:

```bat
run-server.bat
run-client.bat
```

Trong client: giữ tên bất kỳ, địa chỉ `127.0.0.1`, cổng `5050`, bấm **Kết nối**.
Mở thêm một client nữa để thấy tin nhắn của người thứ hai.

## Chạy giữa hai máy trong LAN

1. Máy A chạy `run-server.bat`. Cửa sổ server hiện **ĐỊA CHỈ LAN**, ví dụ
   `192.168.1.42:5050`. Bấm **Sao chép** để copy.
2. Máy B chạy `run-client.bat`, nhập địa chỉ đó và cổng `5050`.
3. Nếu không kết nối được: cho phép Java qua Windows Firewall ở máy A
   (lần đầu chạy Windows sẽ hỏi), và kiểm tra hai máy có cùng mạng LAN không.

Muốn chat qua Internet: mở cổng 5050 trên router (port forwarding) về máy chạy server,
rồi client nhập IP công khai. Cách này không mã hoá — chỉ nên dùng để thử.

## Cấu trúc

| Tệp | Vai trò |
| --- | --- |
| `ChatServer.java` | Giao diện server + lớp `Hub` xử lý giao thức (dùng chung cho chế độ `--console`) |
| `ChatClient.java` | Toàn bộ client, **tự chứa** — copy mỗi tệp này sang máy khác vẫn chạy |
| `run-server.bat`, `run-client.bat` | Biên dịch UTF-8 rồi chạy |

Luồng trong server: một luồng `accept`, mỗi kết nối một luồng đọc. Ghi dùng
`PrintWriter` có `autoflush` nên không cần luồng ghi riêng — với vài chục byte mỗi tin
nhắn thì chi phí không đáng kể.

Luồng trong client: một luồng đọc socket, mọi cập nhật giao diện được đẩy về EDT bằng
`SwingUtilities.invokeLater`, nên giao diện không bao giờ bị chặn bởi mạng.

## Giao thức

Văn bản UTF-8, mỗi thông điệp một dòng. Mọi chuỗi tự do (tên, nội dung) được **base64**
để dấu cách, dấu xuống dòng hay tiếng Việt không thể phá khung truyền.

| Chiều | Thông điệp | Ý nghĩa |
| --- | --- | --- |
| Client → Server | `HELLO <tên>` | Đăng nhập với tên hiển thị |
| Client → Server | `MSG <nội dung>` | Gửi tin nhắn |
| Client → Server | `TYPING <0\|1>` | Bắt đầu/dừng gõ |
| Client → Server | `QUIT` | Rời phòng |
| Server → Client | `WELCOME <tên> <tên phòng>` | Tên thực tế được cấp (có thể bị thêm `(2)`) |
| Server → Client | `ROSTER <tên>...` | Danh sách người online |
| Server → Client | `MSG <người gửi> <nội dung> <mili giây>` | Tin nhắn |
| Server → Client | `SYS <nội dung>` | Thông báo hệ thống |
| Server → Client | `TYPING <tên> <0\|1>` | Ai đang gõ |

Ví dụ một dòng thực tế:

```text
MSG TW4g  SGNo w6BvIG3hu5FpIG5nxqFy4g==  1732278123456
```

## Hạn chế

- Không mã hoá (TLS) và không có mật khẩu phòng.
- Chưa gửi được tệp/ảnh, chưa có emoji màu (Java2D không tự đổi font cho emoji,
  ký tự emoji có thể hiện thành ô vuông).
- Server giữ tối đa 60 tin gần nhất trong RAM; tắt server là mất.
- Một phòng duy nhất, chưa có nhiều phòng riêng hay tin nhắn riêng.
