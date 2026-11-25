# JarStudio

Ứng dụng Windows để kéo thả JAR, đọc/sửa Java, chỉnh hằng số và lưu JAR trực tiếp từ giao diện. Bố cục được tổ chức theo cách quen thuộc của dnSpy: Assembly Explorer, tab code, Output, Analyze, Call Stack, Locals, Watch và Breakpoints.

Đây là Java/JAR editor và JVM debugger riêng. Không tuyên bố tương đương 99% dnSpy, không sử dụng tên/icon dnSpy làm tên ứng dụng và không chỉnh sửa assembly .NET/C#.

## Dùng bản Windows EXE

Mở `dist/windows/JarStudio/JarStudio.exe`. Giữ nguyên thư mục `JarStudio` kèm `app/` và `runtime/` khi chép sang máy khác; không chỉ chép một mình launcher EXE. Bản này mang runtime Java và compiler, không yêu cầu cài Java hay Python để sử dụng.

Bản EXE đi kèm Java 17, hỗ trợ biên dịch class Java 1.1–17. Class phiên bản mới hơn vẫn có thể đọc và sửa hằng số; để biên dịch Java 18–25, chạy bản JAR bằng JDK tương ứng hoặc mới hơn.

## Dùng bản JAR

`dist/JarStudio.jar` chạy bằng JDK 17 trở lên. JDK 25 được khuyên dùng khi chỉnh class Java 22–25. Các compiler/decompiler và thư viện Java ME cần thiết đã được nhúng trong JAR.

```powershell
java --add-modules=jdk.jdi,jdk.compiler -Xmx2g -jar dist/JarStudio.jar
```

Hoặc mở `run.cmd`. Lệnh này ưu tiên bản EXE đã build, sau đó tìm Java sẵn có.

## Luồng thao tác chính

1. Kéo một hay nhiều JAR vào cửa sổ hoặc nhấn **Ctrl+O**.
2. Mở class/hàm trong **Assembly Explorer**. Tab **Java** có tô màu cú pháp và code folding; **Bytecode** là bản đọc ASM.
3. Sửa Java rồi nhấn **F6**. Lỗi được đưa vào bảng **Lỗi biên dịch**; bấm vào lỗi để tới dòng tương ứng.
4. Nhấn **Ctrl+S**, chọn tên JAR đầu ra. Lần đầu ứng dụng gợi ý `ten-jar-edited.jar`.

Class lồng (`Outer$Inner`) được sửa Java thông qua source của class ngoài để compiler tạo đúng cấu trúc. Để sửa hằng số hoặc xem bytecode riêng của class con, bấm chuột phải class đó → **Bytecode / Hằng số class này**.

Ứng dụng kiểm CRC trước khi thay tệp đầu ra. Tài nguyên không sửa được giữ nguyên nội dung. Khi sửa JAR có chữ ký, chữ ký/digest cũ bị loại khỏi bản đã sửa; ứng dụng không tự ký lại JAR.

### Class bị làm rối, không biên dịch lại được

Mở tab **Hằng số**, chọn chuỗi hoặc số và bấm **Sửa giá trị**, sau đó F6/Ctrl+S. Cách này sửa bytecode thay vì phải tái biên dịch toàn bộ source.

Menu chuột phải của hàm có **Thay hàm bằng giá trị mặc định** để thay thân hàm bằng `return 0`, `false`, `null` hoặc `return` cho void. Constructor, abstract và native không dùng thao tác này.

Không thể đảm bảo mọi class decompile từ JAR obfuscated đều trở thành Java có thể compile. Có thể đổi CFR/Vineflower rồi tải lại Java; thêm JAR phụ thuộc qua **Thư viện** nếu thiếu class.

## Tính năng kiểu IDE

- **Mở tệp thường (txt/java/xml/properties/…):** Tệp → **Mở tệp…** (Ctrl+Shift+O) hoặc nút trên thanh công cụ. Tệp `.class` riêng lẻ cũng mở được (dịch ngược). Mở nhiều tệp, mỗi tệp một tab; **Mở gần đây** lưu tới 20 tệp.
- **Sửa và lưu tệp trực tiếp:** tab tệp thường có thể soạn như trình soạn thảo; **Ctrl+S** ghi thẳng ra đĩa (khác lưu JAR). Nếu tệp thay đổi ngoài ứng dụng, có cảnh báo trước khi ghi đè.
- **Tìm / thay trong tab:** Ctrl+F (tìm, giữ chuỗi đang chọn), Ctrl+H (tìm và thay tất cả), F3 (kết quả tiếp theo, vòng quanh).
- **Tự hoàn thành:** Ctrl+Space (hoặc Shift+Insert) gợi ý từ có trong tệp, tên thành viên class đang mở và từ khóa Java; chọn để chèn.
- **Bảng cấu trúc (Outline):** panel **Cấu trúc** liệt kê class, hàm và field; bấm đúp để nhảy tới dòng khai báo (mở tab khác nếu member thuộc JAR khác).
- **Đi đến class:** Ctrl+T tìm class theo tên đủ package trong mọi JAR đang mở và mở thẳng.
- **Biên dịch hàng loạt:** Ctrl+Shift+F6 biên dịch mọi class đang sửa trong các JAR, mỗi nhóm gom theo phiên bản class + package để class phụ thuộc biên dịch cùng nhau.
- **Chạy class main:** menu Chạy → **Chạy class main…**; tự nhận `Main-Class`, biên dịch thay đổi đang mở rồi chạy và ghi đầu ra vào Output.
- **Lỗi biên dịch trên lề:** khi F6 lỗi, dấu chấm ở gutter và bảng **Lỗi biên dịch**; bấm lỗi để tới dòng.
- **Gom code / chú thích:** Ctrl+M gom/mở code folding; menu Mã nguồn → chú thích dòng.

Tự hoàn thành và outline hỗ trợ thao tác, không phải index toàn bộ project hay phân tích kiểu như IDE đầy đủ. Tệp nhị phân (không phải text) mở ở chế độ xem hex, không thể sửa trực tiếp.

## Gỡ lỗi JVM

- **F9:** bật/tắt breakpoint theo dòng.
- Menu chuột phải của hàm → **Ngắt khi vào hàm**: không phụ thuộc line debug symbols.
- **F5:** chạy/tiếp tục. JAR có `Main-Class` được nhận tự động; JAR Java ME mở qua MicroEmulator.
- **F10 / F11 / Shift+F11:** step over / into / out.
- **Shift+F5:** dừng JVM do ứng dụng khởi chạy; với JVM attach, thao tác sẽ detach.
- **Locals:** mở object/array để xem field/phần tử; bấm đúp để sửa số, boolean, char, String hoặc đặt object về null.
- **Threads:** xem thread và bấm đúp khi JVM đang tạm dừng để xem stack/locals của thread đó.
- **Watch:** biến cục bộ và đường dẫn field như `this.name`, `object.field`; chưa phải trình đánh giá toàn bộ biểu thức Java.
- **Gắn vào JVM:** socket JDWP, mặc định `127.0.0.1:5005`.
- **Áp dụng code vào JVM đang chạy:** dùng HotSwap tiêu chuẩn của JVM; thông thường chỉ đổi thân hàm. Thêm/xóa field hoặc method có thể cần khởi động lại target.

Số dòng Java decompile không nhất thiết trùng `LineNumberTable` cũ. Để đặt breakpoint chính xác theo source đang xem, biên dịch class bằng F6 trước rồi chạy debug. JAR thiếu debug symbols vẫn có thể dùng breakpoint theo hàm.

## Chức năng và giới hạn

| Phần | Phạm vi |
|---|---|
| Mở JAR/ZIP, kéo thả nhiều JAR | Có |
| Dịch ngược Java | CFR và Vineflower |
| Sửa Java và biên dịch class | Javac cho Java mới; ECJ cho target cũ/Java ME |
| Java ME | Có CLDC 1.1/MIDP 2.0 API; giữ target classfile cũ |
| Bytecode | Đọc, sửa hằng số và thay thân hàm mặc định; chưa có assembler JVM tổng quát |
| Tài nguyên | Sửa text, xem ảnh/hex, thêm/thay/xóa tệp |
| Mở/sửa tệp thường | txt/java/xml/properties/…; lưu thẳng ra đĩa như trình soạn thảo |
| Kiểu IDE | Tìm/thay trong tab, tự hoàn thành (Ctrl+Space), outline, đi đến class (Ctrl+T), biên dịch hàng loạt, chạy main |
| Tìm kiếm | Class, hàm, field, chuỗi và direct call/field references |
| Debug | Breakpoint, step, stack, locals, basic watch, sửa biến, HotSwap |
| .NET/Unity C#, IL, BAML | Không phải nền tảng được hỗ trợ |
| Watch đầy đủ, conditional breakpoints, script console, chống anti-debug | Chưa triển khai |
| UI docking của WPF/dnSpy | Bố cục tương tự bằng Swing; không phải bản sao pixel 99% |

## Build từ source

Cần Python 3 và JDK 17 trở lên; bản hiện tại được build bằng JDK 25. Các dependency được khóa phiên bản/hash trong `tools/dependencies.lock.json`; nguồn tải Maven Central được ghi theo từng artifact.

```powershell
python tools/bootstrap.py
python tools/build.py --tests
python tools/build.py --exe
python tools/test.py --packaged --gui
```

Có thể chỉ định JDK dùng cho runtime EXE bằng `--runtime-jdk C:/duong-dan/jdk-17` hoặc biến `JARSTUDIO_RUNTIME_JDK`. Compiler và debugger được đóng gói cùng runtime; file `lib/ct.sym` được giữ lại để `javac --release` biên dịch đúng API Java cũ.

`src/main/java` chứa source ứng dụng, `src/test/java` chứa kiểm tra backend, debugger thật và GUI. Bản build Windows dùng `jlink` + `jpackage app-image` để tạo launcher EXE và runtime riêng.
