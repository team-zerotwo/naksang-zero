import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { errorText, guardianApi } from "../api";
import { formatClock, formatKoreanDate } from "../format";
import { IconAlert, IconCheck, IconChevron, IconCopy, IconPhone, IconPin, IconWalk } from "../icons";
import { useSession } from "../session";
import type { EventDetail, EventType } from "../types";

type AlertView = {
  id: string;
  type: EventType;
  title: string;
  when: string;
  sample: boolean;
  isTest: boolean;
  place: string;
  address: string;
  detail: string;
  responded: boolean;
};

const mockAlerts: Record<string, AlertView> = {
  "demo-fall": {
    id: "demo-fall",
    type: "fall_alert",
    title: "넘어짐이\n감지됐어요",
    when: "30초간 응답 없음 · 오후 3:42",
    sample: true,
    isTest: false,
    place: "안방 · 공동현관 #1234",
    address: "서울시 ○○구 ○○로 12",
    detail: "101동 502호",
    responded: false,
  },
  oct21: {
    id: "oct21",
    type: "responded",
    title: "보호자가\n가고 있어요",
    when: "10월 21일 오전 9:12 · 2분 뒤 확인",
    sample: true,
    isTest: false,
    place: "안방 · 공동현관 #1234",
    address: "서울시 ○○구 ○○로 12",
    detail: "101동 502호",
    responded: true,
  },
  oct9: {
    id: "oct9",
    type: "responded",
    title: "보호자가\n가고 있어요",
    when: "10월 9일 오후 7:30 · ‘가고 있어요’",
    sample: true,
    isTest: false,
    place: "안방 · 공동현관 #1234",
    address: "서울시 ○○구 ○○로 12",
    detail: "101동 502호",
    responded: true,
  },
  sep28: {
    id: "sep28",
    type: "fall_alert",
    title: "넘어짐이\n감지됐어요",
    when: "9월 28일 새벽 2:05 · 확인을 기다려요",
    sample: true,
    isTest: false,
    place: "안방 · 공동현관 #1234",
    address: "서울시 ○○구 ○○로 12",
    detail: "101동 502호",
    responded: false,
  },
  sep3: {
    id: "sep3",
    type: "responded",
    title: "보호자가\n가고 있어요",
    when: "9월 3일 오후 1:47 · 5분 뒤 확인",
    sample: true,
    isTest: false,
    place: "안방 · 공동현관 #1234",
    address: "서울시 ○○구 ○○로 12",
    detail: "101동 502호",
    responded: true,
  },
};

function fromDetail(event: EventDetail, responded: boolean): AlertView {
  const type = responded ? "responded" : event.type;
  const when =
    type === "self_cancelled"
      ? `${formatKoreanDate(event.detectedAt)} ${formatClock(event.detectedAt)} · 본인이 괜찮다고 하셨어요`
      : type === "responded"
        ? `${formatKoreanDate(event.detectedAt)} ${formatClock(event.detectedAt)} · 가고 있어요`
        : `30초간 응답 없음 · ${formatClock(event.detectedAt)}`;
  const place = [event.roomNote, event.entryMethod ? `공동현관 ${event.entryMethod}` : null].filter(Boolean).join(" · ");
  return {
    id: event.id,
    type,
    title: type === "self_cancelled" ? "본인이 괜찮다고\n하셨어요" : type === "responded" ? "보호자가\n가고 있어요" : "넘어짐이\n감지됐어요",
    when,
    sample: false,
    isTest: event.isTest,
    place: place || "주소 아래를 확인해 주세요",
    address: event.address,
    detail: event.detail,
    responded: type === "responded",
  };
}

export function AlertScreen() {
  const { eventId = "" } = useParams();
  const navigate = useNavigate();
  const { mock, token, mockState, ackMock, setToken } = useSession();
  const [view, setView] = useState<AlertView | null>(null);
  const [error, setError] = useState("");
  const [copied, setCopied] = useState(false);
  const [callNote, setCallNote] = useState(false);
  const [pending, setPending] = useState(false);

  useEffect(() => {
    document.title = "낙상 알림 · 낙상제로";
    if (mock) {
      const base = mockAlerts[eventId] ?? { ...mockAlerts["demo-fall"], id: eventId };
      const address = mockState.address;
      const responded = base.responded || mockState.ackedIds.includes(base.id);
      setView({
        ...base,
        type: responded && base.type === "fall_alert" ? "responded" : base.type,
        title: responded && base.type !== "self_cancelled" ? "보호자가\n가고 있어요" : base.title,
        responded,
        address: address.address,
        detail: address.detail,
        place: [address.roomNote, address.entryMethod ? `공동현관 ${address.entryMethod}` : null].filter(Boolean).join(" · "),
      });
      return;
    }
    if (!token) return;
    let stop = false;
    guardianApi
      .event(token, eventId)
      .then((event) => {
        if (!stop) setView(fromDetail(event, event.type === "responded"));
      })
      .catch((err) => {
        if (stop) return;
        if (err instanceof Error && "status" in err && (err as { status: number }).status === 401) {
          setToken(null);
          return;
        }
        setError(errorText(err));
      });
    return () => {
      stop = true;
    };
  }, [mock, token, eventId, mockState, setToken]);

  useEffect(() => {
    const meta = document.querySelector('meta[name="theme-color"]');
    if (!meta || !view) return;
    const previous = meta.getAttribute("content");
    meta.setAttribute("content", view.type === "fall_alert" ? "#B3261E" : "#35634A");
    return () => {
      if (previous) meta.setAttribute("content", previous);
    };
  }, [view]);

  async function copyAddress() {
    if (!view) return;
    try {
      await navigator.clipboard.writeText(`${view.address} ${view.detail}`);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      setCopied(false);
      setError("주소를 복사하지 못했어요.");
    }
  }

  async function ack() {
    if (!view || view.responded || view.type === "self_cancelled") return;
    setPending(true);
    setError("");
    try {
      if (mock) ackMock(view.id);
      else if (token) await guardianApi.ack(token, view.id);
      setView({ ...view, type: "responded", responded: true, title: "보호자가\n가고 있어요" });
    } catch (err) {
      setError(errorText(err));
    } finally {
      setPending(false);
    }
  }

  if (!view) {
    return (
      <main className="screen alert calm">
        <p className="loading">{error || "알림을 불러오고 있어요"}</p>
        {error ? (
          <Link className="btn ghost" to="/home">
            홈으로
          </Link>
        ) : null}
      </main>
    );
  }

  const danger = view.type === "fall_alert";
  return (
    <main className={`screen alert${danger ? " danger" : " calm"}`}>
      <header className="alert-hero">
        <img className="worry" src={danger ? "/assets/house-worry.svg" : "/assets/house-relief.svg"} alt="" />
        <div className="alert-copy">
          <p className="kicker">
            {danger ? <IconAlert /> : <IconCheck />}
            {danger ? "낙상 알림" : view.type === "self_cancelled" ? "응답" : "확인함"}
          </p>
          <h1 className="display">{view.title}</h1>
          <p className="when">
            {view.when}
            {view.sample ? <><br />예시 데이터</> : null}
            {view.isTest ? <><br />테스트로 보낸 알림</> : null}
          </p>
        </div>
        <p className="place">
          <IconPin className="sm" />
          {view.place}
        </p>
      </header>
      <svg className="wave" viewBox="0 0 390 60" preserveAspectRatio="none" aria-hidden="true">
        <path d="M0 0H390V20C330 60 260 0 190 30S60 50 0 24Z" />
      </svg>
      <img className="town" src={danger ? "/assets/town-alert.svg" : "/assets/town.svg"} alt="" />
      <div className="sheet-wrap">
        <div className="sheet-back" />
        <section className="sheet">
          <div className="address-head">
            <div>
              <div className="lbl">어르신 댁 주소</div>
              <div className="display address-title">{view.address}</div>
              <div className="address-detail">{view.detail}</div>
            </div>
            <button type="button" className="copy" onClick={() => void copyAddress()}>
              <IconCopy />
              {copied ? "복사됨" : "복사"}
            </button>
          </div>
          {danger || view.type === "responded" ? (
            <a className={`btn ${danger ? "danger" : ""}`} href="tel:119">
              <IconPhone className="lg" />
              119 전화 열기
            </a>
          ) : null}
          {danger ? <p className="fine">전화 앱에 119만 채워 열려요. 자동 신고가 아니에요.</p> : null}
          {view.type !== "self_cancelled" ? (
            <button type="button" className="row linkish" onClick={() => setCallNote(true)}>
              <IconPhone />
              <span>
                <b>어르신께 전화 걸기</b> <span className="sub">어르신 폰으로 바로</span>
              </span>
              <IconChevron className="sm" />
            </button>
          ) : null}
          {callNote ? <p className="fine">등록된 전화번호가 없어요. 위급하면 119 버튼을 눌러 전화 앱만 여세요.</p> : null}
          {view.type !== "self_cancelled" ? (
            <button type="button" className="row linkish" onClick={() => void ack()} disabled={pending || view.responded}>
              <IconWalk />
              <span>
                <b>{view.responded ? "가고 있다고 알렸어요" : "제가 가고 있어요"}</b>{" "}
                <span className="sub">{view.responded ? "다른 보호자에게 전달했어요" : "다른 보호자에게 알려요"}</span>
              </span>
              {view.responded ? <IconCheck className="sm" /> : <IconChevron className="sm" />}
            </button>
          ) : null}
          {error ? <p className="warn-line">{error}</p> : null}
          <button type="button" className="btn ghost done" onClick={() => navigate("/home")}>
            <IconCheck />
            {view.type === "self_cancelled" ? "홈으로" : "확인했어요"}
          </button>
        </section>
      </div>
    </main>
  );
}
