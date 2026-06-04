import 'package:flutter/material.dart';

import '../../app/theme/app_colors.dart';
import '../utils/status_utils.dart';

class StatusBadge extends StatelessWidget {
  const StatusBadge({
    super.key,
    required this.status,
    this.compact = false,
  });

  final String status;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final color = StatusUtils.color(status);
    return Container(
      padding: EdgeInsets.symmetric(
        horizontal: compact ? 9 : 12,
        vertical: compact ? 6 : 8,
      ),
      decoration: BoxDecoration(
        color: color.withOpacity(0.10),
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: color.withOpacity(0.22)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(StatusUtils.icon(status), size: compact ? 14 : 16, color: color),
          const SizedBox(width: 6),
          Text(
            StatusUtils.label(status),
            style: TextStyle(
              color: color == AppColors.offline ? AppColors.slateGraphite : color,
              fontWeight: FontWeight.w900,
              fontSize: compact ? 11 : 12,
            ),
          ),
        ],
      ),
    );
  }
}
