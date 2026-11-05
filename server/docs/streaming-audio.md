# Dùng ONPlay STT qua Internet

Tài liệu này dành cho ứng dụng kết nối tới dịch vụ công khai **[stt.qiverse.top](https://stt.qiverse.top)**. Đây là WebSocket để gửi âm thanh và nhận kết quả nhận dạng trực tiếp; mở địa chỉ bằng trình duyệt không tạo trang web.

## Kết nối

- WebSocket URL: `wss://stt.qiverse.top/ws`
- Gửi Origin: `https://stt.qiverse.top`
- Âm thanh đầu vào: PCM signed 16-bit little-endian, mono, 16 kHz; không gửi header WAV.

Máy chủ chấp nhận một phiên nhận dạng tại một thời điểm. Ứng dụng nên báo cho người dùng nếu nhận sự kiện `error` nói rằng engine đang bận, và cho phép thử lại sau.

## Bắt đầu phiên

Sau khi mở WebSocket, gửi một JSON cấu hình làm message đầu tiên, trong vòng 10 giây. Cấu hình tối thiểu để nhận dạng tiếng nói và dịch sang tiếng Việt:

```json
{"source_language":"auto","target_language":"vi"}
```

Để chỉ nhận dạng, bỏ `target_language` hoặc đặt thành chuỗi rỗng:

```json
{"source_language":"auto"}
```

Chờ sự kiện `{"type":"ready"}` trước khi gửi âm thanh. Các tùy chọn thường dùng:

| Tùy chọn | Mặc định | Ý nghĩa |
|---|---|---|
| `source_language` | `auto` | Ngôn ngữ đầu vào: `auto`, `vi`, `en`, `zh`, `ja`, `ko`, `th`, `id`, `ru`, `ar`, `fr`, `de`, `es` |
| `target_language` | rỗng | Ngôn ngữ dịch: các mã ở trên; rỗng để tắt dịch |
| `translation_mode` | `standard` | `standard` hoặc `context` để tinh chỉnh bản dịch theo các câu trước |
| `speech` | `false` | Nhận WAV đọc bản dịch; cần `target_language`, hiện hỗ trợ giọng Việt và Anh |
| `speakers` | `false` | `true` để nhận nhãn người nói trực tiếp và phân đoạn cuối phiên; `"live"` chỉ bật nhãn trực tiếp |
| `audio_encoding` | `base64` | Cách nhận WAV đọc: `base64` hoặc `binary` |
| `store` | `false` | Yêu cầu máy chủ lưu transcript phiên này |

Các tùy chọn kỹ thuật `threads` (1–16, mặc định 2) và `chunk` (10–1000 ms, mặc định 100) có thể bỏ qua trong ứng dụng thông thường.

## Gửi âm thanh

Gửi các WebSocket **binary message** chứa PCM16. Mỗi message phải có số byte chẵn, từ 2 đến 32.000 byte; 100 ms âm thanh tương đương 3.200 byte. Tách luồng micro thành các khối nhỏ và gửi liên tục, không gom cả bản ghi thành một message lớn.

Khi người dùng dừng nói hoặc kết thúc phiên, gửi text message:

```json
{"type":"end"}
```

Tiếp tục đọc sự kiện cho đến `done`. Đóng kết nối trước đó sẽ hủy phiên và bỏ công việc đang chờ.

## Nhận kết quả

Các sự kiện thông thường là JSON trong WebSocket text message:

| `type` | Nội dung |
|---|---|
| `status` | Thông báo, ví dụ máy chủ đang nạp model |
| `ready` | Có thể bắt đầu gửi âm thanh; kèm `session_id` nếu bật lưu transcript |
| `partial` | Bản nhận dạng tạm thời; nội dung có thể thay đổi khi nhận thêm tiếng |
| `final` | Bản nhận dạng đã chốt cho một đoạn, kèm `id` |
| `translation` | Bản dịch của đoạn, kèm `id` và `revision` |
| `progress` | Số giây âm thanh đã xử lý |
| `warning` | Một tính năng phụ bị giảm; phiên nhận dạng vẫn tiếp tục |
| `error` | Lỗi khiến phiên kết thúc |
| `done` | Máy chủ đã xử lý xong phiên |

`partial` và `final` có thể xuất hiện nhiều lần xen kẽ với `translation`. Dùng `id` để ghép bản dịch với câu gốc. Khi một câu có nhiều bản dịch, giữ revision mới nhất: `provisional` (tạm thời), `live` (dịch nhanh từ câu đã chốt), rồi `context` (bản tinh chỉnh nếu chọn chế độ context). Bản `provisional` có thể viết lại cả câu, không chỉ nối thêm chữ.

Khi bật `speakers`, sự kiện lời nói có thêm `speaker`; giá trị `0` nghĩa là chưa xác định được người nói. Với `speakers: true`, kết quả phân đoạn cuối phiên đến trong sự kiện `speakers` sau khi gửi `end`. Nhãn trực tiếp và nhãn phân đoạn cuối có thể khác nhau.

## Nghe bản dịch

Đặt `speech: true` cùng `target_language` để nhận âm thanh đọc cho bản dịch trực tiếp. Sự kiện `audio` mặc định là JSON có `data` base64 chứa **toàn bộ WAV** của một câu. Giải mã base64 rồi phát lần lượt theo thứ tự sự kiện; tiếp tục nhận WebSocket trong khi phát.

Nếu chọn `audio_encoding: "binary"`, sự kiện âm thanh được gửi thành một binary message: 4 byte đầu là độ dài JSON big-endian, tiếp theo là JSON metadata, phần còn lại là WAV. Các sự kiện khác vẫn là text JSON. Không phải câu dịch nào cũng có âm thanh: máy chủ có thể gửi `audio_skipped` khi hàng đợi phát bị trễ hoặc quá tải. `audio_error` nghĩa là giọng đọc đã dừng cho phiên đó; transcript và bản dịch vẫn tiếp tục.

## Lưu transcript

`store: true` yêu cầu máy chủ lưu transcript phiên và trả `session_id` trong sự kiện `ready`. Tệp được giữ trên máy chủ; hiện không có API công khai để tải lại transcript. Tắt tùy chọn này nếu ứng dụng không cần lưu.

## Xử lý lỗi

- Không mở được kết nối: kiểm tra mạng và thử lại `wss://stt.qiverse.top/ws`.
- Nhận `error` trước `ready`: cấu hình không hợp lệ, máy chủ đang bận hoặc thiếu dịch vụ phụ trợ cần thiết.
- Không thấy `ready`: chờ sự kiện `status`; nếu phiên bị đóng hoặc quá thời gian, kết nối lại và gửi cấu hình đầu tiên.
- Ngừng nhận âm thanh: gửi `{"type":"end"}` để máy chủ hoàn tất và trả `done`.

WebSocket tự trả lời ping/pong để duy trì kết nối. Ứng dụng không cần gửi heartbeat riêng.

## Ghi chú cho người phát triển

`http://127.0.0.1:8765` là Origin cho giao diện dev cũ chạy cùng máy; người dùng dịch vụ công khai không dùng địa chỉ đó. Endpoint loopback của server là cấu hình nội bộ máy chủ, không phải URL để ứng dụng từ xa kết nối. Giao thức không cung cấp xác thực người dùng; Origin chỉ kiểm soát nguồn trình duyệt, không thay thế cơ chế xác thực.
