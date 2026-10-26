import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { errorText, guardianApi } from "../api";
import { TabBar } from "../components/TabBar";
import {
  addOneMonthLabel,
  connectionPercent,
  countsInWindow,
  eventLine,
  formatAgo,
  formatClock,
  formatKoreanDate,
  formatKoreanWhen,
  homeStatus,
  isSameSeoulDay,
} from "../format";
import { IconAlert, IconCalendar, IconCheck } from "../icons";
import { useSession } from "../session";
import type { Address, EventSummary, HomeResponse } from "../types";

type RecentRow = { id: string; text: string; done: boolean };

type HomeView = {
  household: string;
  dayLabel: string;
  title: string;
  badge: string;
  tone: "ok" | "warn";
  lastLine: string;
  connection: number;
  battery: number | null;
  weekCount: number;
  confirmedLabel: string | null;
  nextLabel: string | null;
  recent: RecentRow[];
  sample: boolean;
};

function liveView(home: HomeResponse, events: EventSummary[], address: Address): HomeView {
  const status = homeStatus(home.device.status, home.device.lastSeenAt);
  const counts = countsInWindow(events);
  return {
    household: "어르신 댁",
    dayLabel: home.device.lastSeenAt && isSameSeoulDay(home.device.lastSeenAt) ? "오늘" : home.device.lastSeenAt ? formatKoreanDate(home.device.lastSeenAt) : "오늘",
    title: status.title,
    badge: status.badge,
    tone: status.tone,
    lastLine: home.device.lastSeenAt
      ? `마지막 확인 ${formatClock(home.device.lastSeenAt)} · ${formatAgo(home.device.lastSeenAt)}`
      : "아직 폰 소식이 없어요",
    connection: connectionPercent(home.device.lastSeenAt),
    battery: home.device.battery,
    weekCount: counts.week,
    confirmedLabel: address.addressConfirmedAt ? formatKoreanDate(address.addressConfirmedAt) : null,
    nextLabel: address.addressConfirmedAt ? addOneMonthLabel(address.addressConfirmedAt) : null,
    recent: events.slice(0, 2).map((event) => ({
      id: event.id,
      text: `${formatKoreanWhen(event.detectedAt)} · ${eventLine(event)}`,
      done: event.type !== "fall_alert",
    })),
    sample: false,
  };
}

export function HomeScreen() {
  const { mock, token, mockState, setToken } = useSession();
  const [live, setLive] = useState<HomeView | null>(null);
  const [error, setError] = useState("");
  const view = mock ? mockHome(mockState.confirmedLabel, mockState.nextLabel) : live;

  useEffect(() => {
    document.title = "홈 · 낙상제로";
  }, []);

  useEffect(() => {
    if (mock || !token) return;
    let stop = false;
    const load = async () => {
      try {
        const [home, events, address] = await Promise.all([
          guardianApi.home(token),
          guardianApi.events(token),
          guardianApi.address(token),
        ]);
        if (!stop) {
          setLive(liveView(home, events, address));
          setError("");
        }
      } catch (err) {
        if (!stop) {
          if (err instanceof Error && "status" in err && (err as { status: number }).status === 401) {
            setToken(null);
            return;
          }
          setError(errorText(err));
        }
      }
    };
    void load();
    const timer = window.setInterval(() => void load(), 20000);
    return () => {
      stop = true;
      window.clearInterval(timer);
    };
  }, [mock, token, setToken]);

  if (!view) {
    return (
      <main className="screen home">
        <p className="loading">{error || "소식을 불러오고 있어요"}</p>
      </main>
    );
  }

  const lowBattery = view.battery !== null && view.battery <= 40;
  return (
    <main className="screen home">
      <header className="home-hero">
        <img className="living" src="/assets/living-room.svg" alt="" />
        <div className="hero-top">
          <b>{view.household}</b>
          <span>{view.dayLabel}</span>
        </div>
        <h1 className="display hero-title">{view.title}</h1>
      </header>
      <img className="house-overlap" src="/assets/house-calm.svg" alt="" />
      <p className={`status-block ${view.tone}`}>
        <span className="status-badge">
          {view.tone === "ok" ? <IconCheck className="sm" /> : <IconAlert className="sm" />}
          {view.badge}
        </span>
        <span className="sub">{view.lastLine}</span>
      </p>
      <section className="metrics" aria-label="폰 상태">
        <div>
          <div className="lbl">연결</div>
          <div className={`display metric${view.connection < 70 ? " warn" : ""}`}>{view.connection}%</div>
        </div>
        <div className={lowBattery ? "warn" : undefined}>
          <div className="lbl">폰 배터리</div>
          <div className="display metric">{view.battery === null ? "—" : `${view.battery}%`}</div>
        </div>
        <div>
          <div className="lbl">최근 7일 알림</div>
          <div className="display metric">
            {view.weekCount}
            <span>건</span>
          </div>
        </div>
      </section>
      <p className="address-line">
        <span>
          <IconCalendar className="sm" /> 주소 확인 {view.confirmedLabel ? <b>{view.confirmedLabel}</b> : <b>아직</b>}
        </span>
        <span className="sub">{view.nextLabel ? `다음: ${view.nextLabel}` : "한 달에 한 번"}</span>
      </p>
      <section className="recent">
        <div className="recent-head">
          <b>최근 알림</b>
          <Link to="/history">전체 보기</Link>
        </div>
        {view.recent.length === 0 ? <p className="sub">아직 알림이 없어요</p> : null}
        {view.recent.map((row) => (
          <Link key={row.id} to={`/alert/${row.id}`} className="recent-row">
            <span>{row.text}</span>
            <span className={row.done ? "ok-text" : "warn-text"}>{row.done ? "✓ 확인함" : "⚠ 확인 안 됨"}</span>
          </Link>
        ))}
        {view.sample ? <p className="sub sample">예시 데이터</p> : null}
        {error ? <p className="warn-line">{error}</p> : null}
      </section>
      <TabBar />
    </main>
  );
}

function mockHome(confirmedLabel: string, nextLabel: string): HomeView {
  return {
    household: "어머니 댁",
    dayLabel: "오늘",
    title: "지켜보는\n중이에요",
    badge: "감지 정상",
    tone: "ok",
    lastLine: "마지막 확인 오후 3:40 · 2분 전",
    connection: 99,
    battery: 40,
    weekCount: 0,
    confirmedLabel,
    nextLabel,
    recent: [
      { id: "oct21", text: "10월 21일 오전 9:12 · 2분 뒤 확인", done: true },
      { id: "oct9", text: "10월 9일 오후 7:30 · ‘가고 있어요’", done: true },
    ],
    sample: true,
  };
}
