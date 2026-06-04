import 'package:flutter/material.dart';

import '../../app/theme/app_assets.dart';
import '../../app/theme/app_colors.dart';

class VoraLogo extends StatelessWidget {
  const VoraLogo({
    super.key,
    this.dark = false,
    this.lockup = false,
    this.height = 50,
    this.width,
    this.fit = BoxFit.contain,
  });

  final bool dark;
  final bool lockup;
  final double height;
  final double? width;
  final BoxFit fit;

  @override
  Widget build(BuildContext context) {
    final asset = lockup
        ? (dark ? AppAssets.logoLockupDark : AppAssets.logoLockupLight)
        : AppAssets.logoMark;

    return Image.asset(
      asset,
      width: width,
      height: height,
      fit: fit,
      filterQuality: FilterQuality.high,
      errorBuilder: (_, __, ___) => _FallbackLogo(height: height, dark: dark),
    );
  }
}

class _FallbackLogo extends StatelessWidget {
  const _FallbackLogo({required this.height, required this.dark});

  final double height;
  final bool dark;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: height,
          height: height,
          decoration: BoxDecoration(
            color: dark
                ? AppColors.white.withOpacity(0.08)
                : AppColors.softWhite,
            borderRadius: BorderRadius.circular(14),
            border: Border.all(
              color: dark
                  ? AppColors.white.withOpacity(0.12)
                  : AppColors.borderGray,
            ),
          ),
          child: Icon(
            Icons.bolt_rounded,
            color: dark ? AppColors.white : AppColors.deepTeal,
          ),
        ),
        const SizedBox(width: 10),
        Text(
          'VORA',
          style: TextStyle(
            color: dark ? AppColors.white : AppColors.midnightNavy,
            fontSize: height * 0.48,
            fontWeight: FontWeight.w900,
            letterSpacing: 4,
          ),
        ),
      ],
    );
  }
}