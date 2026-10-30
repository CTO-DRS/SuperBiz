import { NextResponse } from "next/server";
import { ROADMAP, INSPECTION } from "@/lib/roadmap-data";

export const dynamic = "force-static";

/** خارطة الطريق الذكية + تقرير الفحص — مطابقة للوثائق في GitHub */
export async function GET() {
  return NextResponse.json({
    roadmap: ROADMAP,
    inspection: INSPECTION,
    repo: "https://github.com/CTO-DRS/SuperBiz",
    docs: {
      roadmapDoc: "https://github.com/CTO-DRS/SuperBiz/blob/main/docs/ROADMAP_SMART_2026.md",
      inspectionDoc: "https://github.com/CTO-DRS/SuperBiz/blob/main/docs/INSPECTION_REPORT_V352.md",
    },
  });
}
