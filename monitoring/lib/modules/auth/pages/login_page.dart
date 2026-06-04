import 'package:flutter/material.dart';
import 'package:get/get.dart';

import '../../../app/theme/app_colors.dart';
import '../../../core/widgets/vora_logo.dart';
import '../controllers/auth_controller.dart';

class LoginPage extends StatefulWidget {
  const LoginPage({super.key});

  @override
  State<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends State<LoginPage> {
  final _formKey = GlobalKey<FormState>();
  final _usernameController = TextEditingController();
  final _passwordController = TextEditingController();
  final _authController = Get.find<AuthController>();

  @override
  void dispose() {
    _usernameController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  void _submit() {
    if (!_formKey.currentState!.validate()) return;
    _authController.login(
      username: _usernameController.text,
      password: _passwordController.text,
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.midnightNavy,
      body: Stack(
        children: [
          const _BackgroundGlow(),
          Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 36),
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 1120),
                child: LayoutBuilder(
                  builder: (context, constraints) {
                    final isWide = constraints.maxWidth >= 900;
                    return Row(
                      children: [
                        if (isWide) const Expanded(child: _BrandPanel()),
                        if (isWide) const SizedBox(width: 54),
                        Expanded(
                          child: Align(
                            alignment: isWide
                                ? Alignment.centerRight
                                : Alignment.center,
                            child: _LoginCard(
                              formKey: _formKey,
                              usernameController: _usernameController,
                              passwordController: _passwordController,
                              onSubmit: _submit,
                              authController: _authController,
                            ),
                          ),
                        ),
                      ],
                    );
                  },
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _BrandPanel extends StatelessWidget {
  const _BrandPanel();

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.center,
      mainAxisSize: MainAxisSize.min,
      children: [
        const VoraLogo(
    
          lockup: true,
          width: 800,
          height: 300,
          fit: BoxFit.contain,
        ),
        const SizedBox(height: 32),
        const Text(
          'One platform. Every brand.',
          style: TextStyle(
            color: AppColors.white,
            fontSize: 44,
            height: 1.05,
            fontWeight: FontWeight.w900,
            letterSpacing: -1.4,
          ),
        ),
        const SizedBox(height: 18),
        Text(
          'Visibility · Operations · Renewable · Assets',
          style: TextStyle(
            color: AppColors.white.withOpacity(0.72),
            fontSize: 15,
            fontWeight: FontWeight.w700,
            letterSpacing: 3.5,
          ),
        ),
        const SizedBox(height: 34),
        Wrap(
          spacing: 12,
          runSpacing: 12,
          children: const [
            _LoginPill(label: 'Multi-brand hub'),
            _LoginPill(label: 'Industrial monitoring'),
         
          ],
        ),
      ],
    );
  }
}

class _LoginPill extends StatelessWidget {
  const _LoginPill({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: AppColors.white.withOpacity(0.08),
        border: Border.all(color: AppColors.white.withOpacity(0.13)),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: AppColors.white.withOpacity(0.78),
          fontSize: 15,
          fontWeight: FontWeight.w800,
        ),
      ),
    );
  }
}

class _LoginCard extends StatelessWidget {
  const _LoginCard({
    required this.formKey,
    required this.usernameController,
    required this.passwordController,
    required this.onSubmit,
    required this.authController,
  });

  final GlobalKey<FormState> formKey;
  final TextEditingController usernameController;
  final TextEditingController passwordController;
  final VoidCallback onSubmit;
  final AuthController authController;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 440,
      padding: const EdgeInsets.all(30),
      decoration: BoxDecoration(
        color: AppColors.white,
        borderRadius: BorderRadius.circular(30),
        border: Border.all(color: AppColors.white.withOpacity(0.12)),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withOpacity(0.24),
            blurRadius: 42,
            offset: const Offset(0, 24),
          ),
        ],
      ),
      child: Form(
        key: formKey,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Center(child: VoraLogo(lockup: false, height: 110)),
            const SizedBox(height: 24),
            const Text(
              'Connexion',
              style: TextStyle(
                color: AppColors.midnightNavy,
                fontSize: 28,
                fontWeight: FontWeight.w900,
                letterSpacing: -0.5,
              ),
            ),
            const SizedBox(height: 8),
            const Text(
              'Multi-brand renewable monitoring',
              style: TextStyle(
                color: AppColors.mutedText,
                fontSize: 14,
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(height: 28),
            TextFormField(
              controller: usernameController,
              textInputAction: TextInputAction.next,
              decoration: const InputDecoration(
                labelText: 'Username',
                prefixIcon: Icon(Icons.person_outline_rounded),
              ),
              validator: (value) => value == null || value.trim().isEmpty
                  ? 'Username obligatoire'
                  : null,
            ),
            const SizedBox(height: 16),
            TextFormField(
              controller: passwordController,
              obscureText: true,
              onFieldSubmitted: (_) => onSubmit(),
              decoration: const InputDecoration(
                labelText: 'Password',
                prefixIcon: Icon(Icons.lock_outline_rounded),
              ),
              validator: (value) => value == null || value.isEmpty
                  ? 'Password obligatoire'
                  : null,
            ),
            const SizedBox(height: 24),
            Obx(
              () => SizedBox(
                width: double.infinity,
                height: 54,
                child: FilledButton(
                  onPressed: authController.isLoading.value ? null : onSubmit,
                  child: authController.isLoading.value
                      ? const SizedBox(
                          width: 22,
                          height: 22,
                          child: CircularProgressIndicator(
                            strokeWidth: 2.4,
                            color: AppColors.white,
                          ),
                        )
                      : const Text('Se connecter'),
                ),
              ),
            ),
            const SizedBox(height: 18),
            const Center(
              child: Text(
                'One platform. Every brand.',
                style: TextStyle(
                  color: AppColors.deepTeal,
                  fontSize: 12,
                  fontWeight: FontWeight.w900,
                  letterSpacing: 0.8,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _BackgroundGlow extends StatelessWidget {
  const _BackgroundGlow();

  @override
  Widget build(BuildContext context) {
    return Stack(
      children: [
        Positioned(
          top: -190,
          left: -120,
          child: Container(
            width: 460,
            height: 460,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: AppColors.deepTeal.withOpacity(0.16),
            ),
          ),
        ),
        Positioned(
          bottom: -260,
          right: -180,
          child: Container(
            width: 520,
            height: 520,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: AppColors.deepTeal.withOpacity(0.10),
            ),
          ),
        ),
        Positioned.fill(
          child: DecoratedBox(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [
                  AppColors.midnightNavy,
                  AppColors.midnightNavy,
                  AppColors.navySoft.withOpacity(0.98),
                ],
              ),
            ),
          ),
        ),
      ],
    );
  }
}
