import 'dart:typed_data';

class PickedSitePhoto {
  const PickedSitePhoto({
    required this.bytes,
    required this.filename,
  });

  final Uint8List bytes;
  final String filename;
}

Future<PickedSitePhoto?> pickSitePhoto() async => null;
