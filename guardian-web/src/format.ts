import type { DeviceStatus, EventSummary } from "./types";

const SEOUL = "Asia/Seoul";

function parts(date: Date, options: Intl.DateTimeFormatOptions) {
  return new Intl.DateTimeFormat("ko-KR", { timeZone: SEOUL, ...options }).format(date);
}

export function formatKoreanDate(iso: string): string {
  return parts(new Date(iso), { month: "long", day: "numeric" });
}

export function formatClock(iso: string): string {
  return parts(new Date(iso), { hour: "numeric", minute: "2-digit", hour12: true });
}

export function formatKoreanWhen(iso: string): string {
  return `${formatKoreanDate(iso)} ${formatClock(iso)}`;
}

export function formatAgo(iso: string, now = Date.now()): string {
  const minutes = Math.max(0, Math.floor((now - new Date(iso).getTime()) / 60000));
  if (minutes < 1) return "방금";
  if (minutes < 60) return `${minutes}분 전`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}시간 전`;
  return `${Math.floor(hours / 24)}일 전`;
}

export function seoulYmd(date: Date): { y: number; m: number; d: number } {
  const list = new Intl.DateTimeFormat("en-US", {
    timeZone: SEOUL,
    year: "numeric",
    month: "numeric",
    day: "numeric",
  }).formatToParts(date);
  const n = (type: string) => Number(list.find((part) => part.type === type)?.value);
  return { y: n("year"), m: n("month"), d: n("day") };
}

export function isSameSeoulDay(iso: string, now = new Date()): boolean {
  const a = seoulYmd(new Date(iso));
  const b = seoulYmd(now);
  return a.y === b.y && a.m === b.m && a.d === b.d;
}

export function addOneMonthLabel(iso: string): string {
  const p = seoulYmd(new Date(iso));
  const month = p.m === 12 ? 1 : p.m + 1;
  const year = p.m === 12 ? p.y + 1 : p.y;
  const lastDay = new Date(Date.UTC(year, month, 0)).getUTCDate();
  return `${month}월 ${Math.min(p.d, lastDay)}일`;
}

export function connectionPercent(lastSeenAt: string | null, now = Date.now()): number {
  if (!lastSeenAt) return 0;
  const age = now - new Date(lastSeenAt).getTime();
  if (age < 90_000) return 99;
  if (age < 5 * 60_000) return 70;
  if (age < 30 * 60_000) return 40;
  return 10;
}

export function homeStatus(
  status: DeviceStatus | null,
  lastSeenAt: string | null,
  now = Date.now(),
): { title: string; badge: string; tone: "ok" | "warn" } {
  if (!lastSeenAt) return { title: "아직\n소식이 없어요", badge: "연결 전", tone: "warn" };
  const age = now - new Date(lastSeenAt).getTime();
  if (age > 3 * 60_000) return { title: "소식이\n늦어져요", badge: "확인이 늦어요", tone: "warn" };
  if (status === "countdown") return { title: "확인을\n기다려요", badge: "30초 확인 중", tone: "warn" };
  if (status === "idle") return { title: "감지를\n쉬고 있어요", badge: "대기 중", tone: "warn" };
  return { title: "지켜보는\n중이에요", badge: "감지 정상", tone: "ok" };
}

export function eventLine(event: EventSummary): string {
  if (event.type === "self_cancelled") return "본인이 괜찮다고 하셨어요";
  if (event.type === "fall_alert") return "확인을 기다려요";
  if (!event.respondedAt) return "‘가고 있어요’";
  const minutes = Math.round((new Date(event.respondedAt).getTime() - new Date(event.detectedAt).getTime()) / 60000);
  if (minutes <= 0) return "‘가고 있어요’";
  return `${minutes}분 뒤 ‘가고 있어요’`;
}

export function withinDays(iso: string, days: number, now = Date.now()): boolean {
  return now - new Date(iso).getTime() <= days * 86_400_000;
}

export function averageResponseMinutes(events: EventSummary[]): number | null {
  const diffs = events
    .filter((event) => event.type === "responded" && event.respondedAt)
    .map((event) => (new Date(event.respondedAt!).getTime() - new Date(event.detectedAt).getTime()) / 60000)
    .filter((value) => value >= 0);
  if (!diffs.length) return null;
  return Math.round(diffs.reduce((sum, value) => sum + value, 0) / diffs.length);
}

export type WeekMark = { label: string; count: number; open: boolean };

function weekdayIndex(date: Date): number {
  const name = new Intl.DateTimeFormat("en-US", { timeZone: SEOUL, weekday: "short" }).format(date);
  return Math.max(0, ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"].indexOf(name));
}

function shiftDays(y: number, m: number, d: number, days: number) {
  const next = new Date(Date.UTC(y, m - 1, d + days));
  return { y: next.getUTCFullYear(), m: next.getUTCMonth() + 1, d: next.getUTCDate() };
}

export function weekMarks(events: EventSummary[], now = new Date()): WeekMark[] {
  const today = seoulYmd(now);
  const monday = shiftDays(today.y, today.m, today.d, -weekdayIndex(now));
  const weeks = Array.from({ length: 12 }, (_, index) => {
    const start = shiftDays(monday.y, monday.m, monday.d, -7 * (11 - index));
    return {
      startUtc: Date.UTC(start.y, start.m - 1, start.d) - 9 * 3_600_000,
      label: "",
      count: 0,
      open: false,
    };
  });
  for (const event of events) {
    if (event.type !== "fall_alert" && event.type !== "responded") continue;
    const time = new Date(event.detectedAt).getTime();
    const week = [...weeks].reverse().find((item) => time >= item.startUtc && time < item.startUtc + 7 * 86_400_000);
    if (!week) continue;
    week.count += 1;
    if (event.type === "fall_alert") week.open = true;
    const day = seoulYmd(new Date(event.detectedAt));
    week.label = `${day.m}/${day.d}`;
  }
  return weeks.map(({ label, count, open }) => ({ label, count, open }));
}

export function countsInWindow(events: EventSummary[], now = Date.now()) {
  const recent = events.filter(
    (event) => (event.type === "fall_alert" || event.type === "responded") && withinDays(event.detectedAt, 90, now),
  );
  const week = recent.filter((event) => withinDays(event.detectedAt, 7, now)).length;
  const done = recent.filter((event) => event.type === "responded").length;
  const open = recent.filter((event) => event.type === "fall_alert").length;
  return { total: recent.length, week, done, open, recent };
}
