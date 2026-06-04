import 'package:flutter/material.dart';

import '../../app/theme/app_colors.dart';

class StatusUtils {
  StatusUtils._();

  static String normalize(String? value) {
    final raw = value?.trim().toLowerCase() ?? '';
    if (raw.contains('fault') || raw.contains('alarm')) return 'faulty';
    if (raw.contains('offline') || raw.contains('disconnect')) return 'disconnected';
    if (raw.contains('health') || raw.contains('normal') || raw == 'ok') return 'healthy';
    return raw.isEmpty ? 'unknown' : raw;
  }

  static String label(String? value) {
    switch (normalize(value)) {
      case 'healthy':
        return 'Healthy';
      case 'faulty':
        return 'Faulty';
      case 'disconnected':
        return 'Offline';
      default:
        return 'Unknown';
    }
  }

  static Color color(String? value) {
    switch (normalize(value)) {
      case 'healthy':
        return AppColors.healthy;
      case 'faulty':
        return AppColors.faulty;
      case 'disconnected':
        return AppColors.offline;
      default:
        return AppColors.warning;
    }
  }

  static IconData icon(String? value) {
    switch (normalize(value)) {
      case 'healthy':
        return Icons.check_circle_rounded;
      case 'faulty':
        return Icons.error_rounded;
      case 'disconnected':
        return Icons.pause_circle_filled_rounded;
      default:
        return Icons.help_rounded;
    }
  }
}
