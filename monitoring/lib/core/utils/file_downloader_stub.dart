import 'dart:typed_data';

Future<void> downloadBytes({
  required Uint8List bytes,
  required String filename,
  required String mimeType,
}) async {
  // Fallback desktop/mobile: no-op. Web build uses file_downloader_web.dart.
}
