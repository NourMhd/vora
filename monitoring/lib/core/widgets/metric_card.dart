import 'package:flutter/material.dart';

import '../../app/theme/app_colors.dart';
import 'premium_card.dart';

class MetricCard extends StatelessWidget {
  const MetricCard({
    super.key,
    required this.title,
    required this.value,
    required this.icon,
    this.caption,
    this.accent = AppColors.deepTeal,
  });

  final String title;
  final String value;
  final IconData icon;
  final String? caption;
  final Color accent;

  @override
  Widget build(BuildContext context) {
    return PremiumCard(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 40,
                height: 40,
                decoration: BoxDecoration(
                  color: accent.withOpacity(0.10),
                  borderRadius: BorderRadius.circular(14),
                ),
                child: Icon(icon, color: accent, size: 21),
              ),
              const Spacer(),
              const Icon(Icons.trending_up_rounded, color: AppColors.borderGray, size: 20),
            ],
          ),
          const SizedBox(height: 18),
          Text(
            title,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              color: AppColors.mutedText,
              fontSize: 12,
              fontWeight: FontWeight.w800,
              letterSpacing: 0.5,
            ),
          ),
          const SizedBox(height: 8),
          TweenAnimationBuilder<double>(
            duration: const Duration(milliseconds: 650),
            tween: Tween(begin: 0, end: 1),
            curve: Curves.easeOutCubic,
            builder: (context, animation, child) {
              return Opacity(
                opacity: animation,
                child: Transform.translate(
                  offset: Offset(0, 8 * (1 - animation)),
                  child: child,
                ),
              );
            },
            child: Text(
              value,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                color: AppColors.midnightNavy,
                fontSize: 26,
                fontWeight: FontWeight.w900,
                letterSpacing: -0.8,
              ),
            ),
          ),
          if (caption != null) ...[
            const SizedBox(height: 6),
            Text(
              caption!,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(color: AppColors.mutedText, fontSize: 12),
            ),
          ],
        ],
      ),
    );
  }
}
