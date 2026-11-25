# Mục tiêu giao diện và tính năng

JarStudio giữ workflow kéo thả → xem/sửa → compile → lưu JAR, với các cửa sổ quen thuộc của dnSpy. Các thao tác hiện diện trên menu phải thực sự thực hiện công việc; không tạo nút debugger giả hoặc báo lưu thành công khi chưa biên dịch/kiểm CRC.

## Tính năng kiểu IDE

- Mở/sửa/lưu tệp văn bản thường (txt, java, xml, properties, json, …) như trình soạn thảo: kéo thả trực tiếp vào cửa sổ hoặc Mở tệp (Ctrl+Shift+O); mỗi tệp một tab, Ctrl+S ghi thẳng ra đĩa, cảnh báo khi tệp thay đổi ngoài ứng dụng.
- Tìm/thay trong tab (Ctrl+F, Ctrl+H, F3 vòng quanh), tự hoàn thành dạng cửa sổ popup (Ctrl+Space: gõ để lọc, mũi tên/Enter/đúp chuột chọn, Esc đóng) dựa trên từ trong tệp + thành viên class + từ khóa, bảng cấu trúc class (hàm/field) bấm đúp để nhảy, đi đến class (Ctrl+T), biên dịch hàng loạt mọi class đang sửa (Ctrl+Shift+F6), chạy class main, đánh dấu lỗi biên dịch trên lề gutter, menu Tệp gần đây / JAR gần đây.
- Giới hạn: tự hoàn thành dựa trên từ trong tệp + thành viên class đang mở + từ khóa, không phải phân tích kiểu/static-analysis toàn project; outline dùng thông tin class metadata + quét source, số dòng khớp theo heuristic và có thể lệch với source decompile; kéo thả thư mục bị bỏ qua.

Những giới hạn Java/JVM không được mô tả là tương đương .NET: source decompile có thể không compile; số dòng decompile có thể khác dòng gốc; HotSwap tiêu chuẩn không tùy ý đổi schema class; watch hiện chỉ đọc local/field.

Bản JAR là một ứng dụng Java. Bản EXE là launcher Windows tự chứa runtime trong thư mục phân phối; file EXE không hoạt động độc lập nếu tách khỏi `app` và `runtime`.
