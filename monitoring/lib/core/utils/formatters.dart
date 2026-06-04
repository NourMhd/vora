import 'package:intl/intl.dart';

class Formatters {
  Formatters._();

  static const int defaultDecimals = 3;

  static final _integer = NumberFormat.decimalPattern('fr_FR');
  static final _shortDate = DateFormat('dd MMM yyyy', 'fr_FR');
  static final _shortDateTime = DateFormat('dd MMM yyyy · HH:mm', 'fr_FR');
  static final _apiDay = DateFormat('yyyy-MM-dd');
  static final _solisDate = DateFormat('yyyy/MM/dd');

  static String count(num? value) => _integer.format((value ?? 0).round());

  static String decimal(num? value, {int decimals = defaultDecimals}) {
    final safeDecimals = decimals < 0 ? 0 : decimals;
    final pattern = safeDecimals == 0
        ? '#,##0'
        : '#,##0.${''.padRight(safeDecimals, '0')}';

    return NumberFormat(pattern, 'fr_FR').format(value ?? 0);
  }

  static String energy(num? kwh, {int decimals = defaultDecimals}) {
    final value = (kwh ?? 0).toDouble();

    if (value.abs() >= 1000000) {
      return '${decimal(value / 1000000, decimals: decimals)} GWh';
    }

    if (value.abs() >= 1000) {
      return '${decimal(value / 1000, decimals: decimals)} MWh';
    }

    return '${decimal(value, decimals: decimals)} kWh';
  }

  static String power(num? kw, {int decimals = defaultDecimals}) {
    final value = (kw ?? 0).toDouble();

    if (value.abs() >= 1000) {
      return '${decimal(value / 1000, decimals: decimals)} MWp';
    }

    return '${decimal(value, decimals: decimals)} kWp';
  }

  static String date(DateTime? date) => date == null ? '—' : _shortDate.format(date);
  static String dateTime(DateTime? date) => date == null ? '—' : _shortDateTime.format(date);
  static String apiDay(DateTime date) => _apiDay.format(date);
  static String apiMonth(DateTime date) => DateFormat('yyyy-MM').format(date);
  static String apiYear(DateTime date) => DateFormat('yyyy').format(date);


  static String providerDate(String? raw, {String? brand}) {
    final value = raw?.trim();
    if (value == null || value.isEmpty || value == '—') {
      return '—';
    }

    final parsed = _parseFlexibleDate(value);
    if (brand?.toUpperCase() == 'SOLIS' && parsed != null) {
      return _solisDate.format(parsed);
    }

    if (parsed != null && (value.contains('-') || _looksLikeEpoch(value))) {
      return _shortDate.format(parsed);
    }

    return value;
  }

  static DateTime? _parseFlexibleDate(String raw) {
    final trimmed = raw.trim();

    if (_looksLikeEpoch(trimmed)) {
      final epoch = num.tryParse(trimmed)?.truncate();
      if (epoch != null) {
        final abs = epoch.abs();
        final millis = abs >= 10000000000000000
            ? epoch ~/ 1000000
            : abs >= 100000000000000
                ? epoch ~/ 1000
                : abs < 10000000000
                    ? epoch * 1000
                    : epoch;
        return DateTime.fromMillisecondsSinceEpoch(millis);
      }
    }

    final iso = DateTime.tryParse(trimmed);
    if (iso != null) return iso;

    for (final format in [
      DateFormat('yyyy/MM/dd'),
      DateFormat('dd/MM/yyyy'),
      DateFormat('yyyy/MM/dd HH:mm:ss'),
      DateFormat('yyyy-MM-dd HH:mm:ss'),
    ]) {
      try {
        return format.parseStrict(trimmed);
      } catch (_) {
        // Try next format.
      }
    }

    return null;
  }
  static bool _looksLikeEpoch(String raw) {
    final trimmed = raw.trim();
    if (!RegExp(r'^-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?$').hasMatch(trimmed)) {
      return false;
    }
    final epoch = num.tryParse(trimmed);
    return epoch != null && epoch.abs() >= 1000000000;
  }

  static String cleanLabel(String value) {
    return value.replaceAll('_', ' ').toLowerCase().split(' ').map((part) {
      if (part.isEmpty) return part;
      return part[0].toUpperCase() + part.substring(1);
    }).join(' ');
  }
}