import 'dart:async';
import 'dart:convert';
import 'dart:html' as html;
import 'dart:typed_data';

class PickedSitePhoto {
  const PickedSitePhoto({
    required this.bytes,
    required this.filename,
  });

  final Uint8List bytes;
  final String filename;
}

Future<PickedSitePhoto?> pickSitePhoto() async {
  final completer = Completer<PickedSitePhoto?>();
  final input = html.FileUploadInputElement()
    ..accept = 'image/*'
    ..multiple = false
    ..style.display = 'none';

  void finish(PickedSitePhoto? value) {
    if (!completer.isCompleted) {
      completer.complete(value);
    }
    input.remove();
  }

  input.onChange.first.then((_) {
    final files = input.files;
    if (files == null || files.isEmpty) {
      finish(null);
      return;
    }

    final file = files.first;
    final reader = html.FileReader();

    reader.onError.first.then((_) => finish(null));
    reader.onLoadEnd.first.then((_) {
      final bytes = _readerResultToBytes(reader.result);
      if (bytes == null || bytes.isEmpty) {
        finish(null);
        return;
      }

      finish(PickedSitePhoto(
        bytes: bytes,
        filename: file.name.isEmpty ? 'site-photo.jpg' : file.name,
      ));
    });

    reader.readAsArrayBuffer(file);
  });

  // Keep the input alive in the DOM until the browser finishes reading the file.
  html.document.body?.append(input);
  input.click();
  return completer.future;
}

Uint8List? _readerResultToBytes(Object? result) {
  if (result == null) {
    return null;
  }

  if (result is Uint8List) {
    return result;
  }

  if (result is ByteBuffer) {
    return result.asUint8List();
  }

  if (result is List<int>) {
    return Uint8List.fromList(result);
  }

  if (result is String) {
    final commaIndex = result.indexOf(',');
    if (result.startsWith('data:') && commaIndex >= 0) {
      return base64Decode(result.substring(commaIndex + 1));
    }
  }

  return null;
}
