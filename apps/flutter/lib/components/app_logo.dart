import 'package:flutter/material.dart';

class AppLogo extends StatelessWidget {
  const AppLogo({super.key, this.size = 64});
  final double size;

  @override
  Widget build(BuildContext context) => ClipRRect(
        borderRadius: BorderRadius.circular(size / 4),
        child: Image.asset(
          'assets/icon/icon.png',
          width: size,
          height: size,
          semanticLabel: 'c001apk 标志',
        ),
      );
}
