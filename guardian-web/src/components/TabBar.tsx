import { NavLink } from "react-router-dom";
import { IconGear, IconHome, IconList } from "../icons";

const tabs = [
  { to: "/home", label: "홈", icon: IconHome },
  { to: "/history", label: "기록", icon: IconList },
  { to: "/settings", label: "설정", icon: IconGear },
];

export function TabBar() {
  return (
    <nav className="tabbar" aria-label="주요 메뉴">
      {tabs.map((tab) => (
        <NavLink key={tab.to} to={tab.to} className={({ isActive }) => (isActive ? "on" : undefined)}>
          <tab.icon className="lg" />
          {tab.label}
        </NavLink>
      ))}
    </nav>
  );
}
