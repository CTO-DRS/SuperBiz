import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",
  /* [فحص W1] ignoreBuildErrors أُلغي: كان يقنّع أخطاء أنواع حقيقية
     (خلل ReferenceError في مسار السدود + hooks مفقودة) — typecheck نظيف الآن والبوابة مفروضة */
  typescript: {
    ignoreBuildErrors: false,
  },
  reactStrictMode: false,
};

export default nextConfig;
