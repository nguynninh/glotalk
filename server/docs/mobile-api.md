# ONPlay STT — Tài liệu tích hợp Mobile

Cập nhật: 02/11/2026. Tài liệu mô tả API hiện đang triển khai.

HTTPS text translation and Android/iOS configuration: [translation-api.md](translation-api.md).
The WAV endpoint below remains unchanged.

## 1. Endpoint

```text
POST https://stt.qiverse.top/api/transcribe
```

Gửi file audio bằng HTTPS POST. Server nhận dạng nội dung, phân đoạn người nói và trả một JSON sau khi xử lý xong. Không cần kết nối WebSocket, không có kết quả partial qua endpoint này.

Hiện chưa yêu cầu API key, Authorization hoặc Origin cho POST.

## 2. Request

| Thành phần | Giá trị |
|---|---|
| Method | `POST` |
| Content-Type | `audio/wav` |
| Content-Length | Số byte của toàn bộ file WAV, bao gồm header |
| Body | Nguyên bytes file WAV, bao gồm WAV header |

Không gửi multipart/form-data, JSON, Base64 hoặc raw PCM thiếu WAV header. Không gửi bằng `Transfer-Encoding: chunked`; chọn request body có kích thước xác định để thư viện HTTP đặt Content-Length.

### Định dạng audio

| Thuộc tính | Yêu cầu |
|---|---|
| Container | WAV RIFF, PCM không nén |
| Sample format | Signed PCM 16-bit little-endian |
| Sample rate | 16.000 Hz |
| Số kênh | 1 (mono) |
| Thời lượng | Lớn hơn 0, tối đa 60 giây |
| Dung lượng toàn file | Tối đa 2 MiB = 2.097.152 bytes |

Mobile cần chuyển audio về định dạng trên trước khi gửi. Đổi đuôi `.m4a` thành `.wav` không chuyển đổi codec. Endpoint chưa nhận trực tiếp M4A, AAC, MP3 hoặc MP4. Không có tham số cấu hình bổ sung trong request hiện tại.

### Ví dụ cURL

Chạy từ thư mục chứa `audio.wav` đúng định dạng:

```bash
curl --fail-with-body --max-time 120 \
  'https://stt.qiverse.top/api/transcribe' \
  -H 'Content-Type: audio/wav' \
  --data-binary '@audio.wav'
```

cURL tự đặt Content-Length. Không dùng `-F` vì endpoint không nhận multipart.

## 3. Response thành công

HTTP `200`, `Content-Type: application/json`, nội dung Unicode UTF-8.

Ví dụ cấu trúc minh họa:

```json
{
  "type": "speakers",
  "segments": [
    {
      "start": 0.5,
      "end": 2.8,
      "speaker": 0,
      "text": "Xin chào."
    },
    {
      "start": 3.1,
      "end": 4.9,
      "speaker": 1,
      "text": "Chào bạn."
    }
  ]
}
```

| Trường | Kiểu JSON | Ý nghĩa |
|---|---|---|
| `type` | string | Luôn là `speakers` khi thành công |
| `segments` | array | Các đoạn được sắp theo thời điểm bắt đầu |
| `segments[].start` | number | Thời điểm bắt đầu, đơn vị giây từ đầu file |
| `segments[].end` | number | Thời điểm kết thúc, đơn vị giây từ đầu file |
| `segments[].speaker` | integer | Nhãn người nói, bắt đầu từ `0` trong từng request |
| `segments[].text` | string | Nội dung nhận dạng của đoạn; có thể rỗng |

Speaker ID chỉ phân biệt giọng nói trong file, không phải danh tính thật. Không dùng speaker ID để nhận diện cùng một người giữa các request. Thời gian là ranh giới đoạn nói, không phải timestamp từng từ. Các đoạn có thể có khoảng im lặng hoặc chồng lấn.

Khi không phát hiện đoạn nói, ví dụ audio im lặng:

```json
{"type":"speakers","segments":[]}
```

## 4. Response lỗi

Lỗi do ứng dụng trả có dạng:

```json
{"error":"Engine busy; retry after the current session finishes"}
```

| HTTP | Nguyên nhân | Mobile xử lý |
|---|---|---|
| `400` | Content-Length không hợp lệ, body bị thiếu hoặc gửi chunked | Kiểm tra cách tạo HTTP body |
| `404` | Sai endpoint | Dùng đúng `/api/transcribe`, không thêm dấu `/` cuối |
| `408` | Quá thời gian chờ dữ liệu upload | Kiểm tra kết nối trước khi gửi lại |
| `409` | Engine đang xử lý một phiên khác | Chờ rồi thử lại với backoff, không gửi lặp liên tục |
| `413` | Body rỗng, thiếu Content-Length hoặc vượt 2 MiB | Kiểm tra kích thước và Content-Length |
| `415` | Sai Content-Type | Đặt `audio/wav` |
| `422` | WAV lỗi, sai sample rate/kênh/bit depth, hoặc dài quá 60 giây | Chuyển đổi hoặc rút ngắn audio |
| `500` | Engine hoặc dịch vụ xử lý lỗi | Báo lỗi; liên hệ backend nếu lặp lại |
| `503` | Model phân biệt người nói thiếu hoặc không hợp lệ | Liên hệ backend |
| `504` | Inference vượt giới hạn 90 giây | Thử audio ngắn hơn |

Cloudflare hoặc lỗi mạng có thể trả HTML/thông báo khác. Mobile phải kiểm tra HTTP status và Content-Type trước khi parse JSON; không giả định mọi lỗi đều có trường `error`.

## 5. Luồng tích hợp và giới hạn

1. Kết thúc ghi âm hoặc chuẩn bị file WAV đúng định dạng.
2. Kiểm tra thời lượng và dung lượng; gửi một POST có Content-Length.
3. Hiển thị trạng thái đang xử lý trong lúc chờ HTTP response.
4. Khi nhận `200`, đọc `segments`, hiển thị nội dung theo `speaker`; khi phát audio, dùng `start`/`end` để xác định đoạn.
5. Nếu HTTP lỗi, xử lý theo bảng trên. Chỉ retry có giới hạn; request hiện chưa có idempotency key.

- Gợi ý cấu hình timeout phía mobile: khoảng 120 giây tổng request. Đây là cấu hình client đề xuất, không phải cam kết thời gian phản hồi. Server giới hạn inference 90 giây; upload dùng socket timeout 30 giây, không phải giới hạn tổng thời gian upload.
- Chỉ một phiên inference chạy cùng lúc, dùng chung với giao diện WebSocket. Chưa có hàng đợi; phiên đến khi engine bận nhận `409`.
- File upload được lưu tạm và dọn sau khi xử lý hoặc lỗi được xử lý. Không có endpoint lưu/lấy lại kết quả; mobile tự lưu response nếu cần.
- Audio dài hơn 60 giây cần được chia trước khi gửi. Timestamp và nhãn speaker sẽ bắt đầu lại trong từng request; chưa có cơ chế ghép speaker giữa các phần.
- Endpoint hiện công khai, chưa có xác thực. Chưa có cam kết uptime hoặc benchmark độ chính xác phân biệt người nói trên dữ liệu thực tế của mobile.

## 6. Trạng thái kiểm thử

Đã chạy `.venv/bin/python tests/post_transcribe.py`: kiểm tra Content-Type, WAV lỗi, giới hạn kích thước, nhận dạng mẫu tiếng Việt và response rỗng cho audio im lặng.

Đã gọi HTTPS POST qua domain với mẫu tiếng Việt và nhận HTTP `200` cùng `type`, `segments`, `start`, `end`, `speaker`, `text`. Đã chạy `.venv/bin/python tests/ui_smoke.py` thành công sau thay đổi API.

Chưa kiểm thử trực tiếp bằng app Android/iOS hoặc đánh giá độ chính xác trên hội thoại nhiều người của mobile.
