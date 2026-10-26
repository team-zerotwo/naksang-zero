import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { enablePush, errorText, guardianApi } from "../api";
import { TabBar } from "../components/TabBar";
import { IconCheck, IconChevron } from "../icons";
import { useSession } from "../session";

export function SettingsScreen() {
  const navigate = useNavigate();
  const { mock, token, setToken, notif, setNotif, mockState, grantMockNotif } = useSession();
  const [message, setMessage] = useState("");
  const allowed = mock ? mockState.notifGranted : notif === "granted";

  useEffect(() => {
    document.title = "설정 · 낙상제로";
  }, []);

  async function allow() {
    setMessage("");
    if (mock) {
      grantMockNotif();
      setMessage("예시 화면에서 알림을 켠 것으로 표시해요.");
      return;
    }
    if (!token) return;
    try {
      await enablePush(token);
      setNotif("granted");
      setMessage("알림을 허용했어요.");
    } catch (error) {
      setMessage(error instanceof Error && error.message === "unsupported" ? "이 브라우저에서는 알림을 받을 수 없어요." : errorText(error));
    }
  }

  async function reconfirmPush() {
    setMessage("");
    if (mock) {
      navigate("/reconfirm");
      return;
    }
    try {
      const result = await guardianApi.triggerReconfirm();
      setMessage(`주소 재확인 알림을 ${result.sent}곳에 보냈어요.`);
    } catch (error) {
      setMessage(errorText(error) === "알림을 찾지 못했어요." ? "데모 기능이 꺼져 있어요." : errorText(error));
    }
  }

  return (
    <main className="screen settings">
      <img className="history-house" src="/assets/house-calm.svg" alt="" />
      <h1 className="display page-kicker">설정</h1>
      <p className="sub lead">{mock ? "어머니 댁 · 예시 데이터" : "어르신 댁"}</p>
      <div className="settings-list">
        <div className="row">
          <span className={allowed ? "ok-text" : "warn-text"}>{allowed ? <IconCheck className="sm" /> : null}</span>
          <div className="row-copy">
            <b>{allowed ? "알림 허용됨" : "알림이 꺼져 있어요"}</b>
            <div className="sub">넘어짐을 받으려면 알림이 필요해요</div>
          </div>
        </div>
        {!allowed ? (
          <button type="button" className="btn" onClick={() => void allow()}>
            알림 허용
          </button>
        ) : null}
        <button type="button" className="row linkish" onClick={() => navigate("/reconfirm")}>
          <span className="row-copy">
            <b>주소 다시 확인</b>
            <div className="sub">한 달에 한 번, 찾아갈 주소를 봐요</div>
          </span>
          <IconChevron className="sm" />
        </button>
        <button type="button" className="row linkish" onClick={() => void reconfirmPush()}>
          <span className="row-copy">
            <b>재확인 알림 보내기</b>
            <div className="sub">데모용. 서버에서 개발 기능이 켜져 있을 때 가요</div>
          </span>
          <IconChevron className="sm" />
        </button>
        <button
          type="button"
          className="row linkish"
          onClick={() => {
            setToken(null);
            navigate("/");
          }}
        >
          <span className="row-copy">
            <b>연결 해제</b>
            <div className="sub">이 브라우저에 저장된 연결만 지워요</div>
          </span>
          <IconChevron className="sm" />
        </button>
      </div>
      {message ? <p className="status-note">{message}</p> : null}
      <p className="sub footnote">영상은 저장하거나 보내지 않아요. 판정과 주소만 다뤄요.</p>
      <p className="sub footnote">아이폰은 홈 화면에 추가해야 알림이 와요. 공유 → 홈 화면에 추가.</p>
      <TabBar />
    </main>
  );
}
