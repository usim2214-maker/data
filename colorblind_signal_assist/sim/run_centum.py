#!/usr/bin/env python3
"""
부산 해운대구 센텀시티 실제 도로망(OpenStreetMap)에서 SUMO 교통 시뮬레이션을 돌려
'내 차'의 주행 기록을 만든다. 이 기록을 Kotlin 엔진(core)이 읽어 알림을 판단하고, 시연 영상으로 렌더링한다.

    pip install eclipse-sumo==1.21.0 traci==1.21.0 sumolib==1.21.0 pyproj
    python3 sim/run_centum.py            # sim/data/centum_trace.csv, centum_map.txt 생성

주의
- 도로·차로·신호등 위치는 OpenStreetMap 실제 데이터 (© OpenStreetMap contributors, ODbL)
- 신호 주기는 SUMO 가 자동 생성한 값이며 실제 부산 신호 주기가 아님
"""
import math
import os
import random
import subprocess
import sys
import xml.etree.ElementTree as ET

import sumo
import sumolib
import traci

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "data")
OSM = os.path.join(DATA, "centum.osm")
NET = os.path.join(DATA, "centum.net.xml")
TRIPS = os.path.join(DATA, "background.rou.xml")
TRACE = os.path.join(DATA, "centum_trace.csv")
MAP = os.path.join(DATA, "centum_map.txt")
SUMO_HOME = sumo.SUMO_HOME
BIN = os.path.join(SUMO_HOME, "bin")

STEP = 0.1
STOP_LINE_TO_LIGHT_M = 12.0  # Kotlin 엔진의 가정과 같음 (신호등은 교차로 건너편)
REACTION_S = 1.0
URGENT_DECEL = 2.5
HUMAN_DECEL = 1.6  # 평소 운전자의 편안한 감속도


def build_net():
    subprocess.run([
        os.path.join(BIN, "netconvert"), "--osm-files", OSM, "-o", NET,
        "--ramps.guess", "--junctions.join", "--junctions.join-dist", "12",
        "--tls.guess-signals", "--tls.guess-signals.dist", "20", "--tls.join", "--tls.join-dist", "25",
        "--tls.default-type", "static", "--tls.yellow.time", "3",
        "--output.street-names", "true", "--remove-edges.isolated",
        "--keep-edges.by-vclass", "passenger", "--osm.turn-lanes", "true", "--no-turnarounds", "true",
        "--no-warnings", "true",
    ], check=True)


def build_background():
    subprocess.run([
        sys.executable, os.path.join(SUMO_HOME, "tools", "randomTrips.py"), "-n", NET, "-o", os.path.join(DATA, "bg.trips.xml"),
        "-r", TRIPS, "-b", "0", "-e", "900", "-p", "1.6", "--fringe-factor", "8", "--min-distance", "400",
        "--vehicle-class", "passenger", "--seed", "7", "--validate",
    ], check=True, stdout=subprocess.DEVNULL)


def edge_near(net, lat, lon, name=None, heading=None, r=100):
    x, y = net.convertLonLat2XY(lon, lat)
    best = None
    for e, d in net.getNeighboringEdges(x, y, r):
        if not e.allows("passenger") or (name and e.getName() != name):
            continue
        (x1, y1), (x2, y2) = e.getFromNode().getCoord(), e.getToNode().getCoord()
        h = math.degrees(math.atan2(x2 - x1, y2 - y1)) % 360
        if heading is not None and min(abs(h - heading), 360 - abs(h - heading)) > 60:
            continue
        if best is None or d < best[1]:
            best = (e, d)
    return best[0] if best else None


def heading_of(shape, at_end):
    (x1, y1), (x2, y2) = (shape[-2], shape[-1]) if at_end else (shape[0], shape[1])
    return math.degrees(math.atan2(x2 - x1, y2 - y1))


def maneuvers(route):
    """경로의 회전 지점 (누적 거리, 'LEFT'/'RIGHT', 다음 도로 이름)"""
    out, dist = [], 0.0
    for a, b in zip(route, route[1:]):
        dist += a.getLength()
        turn = (heading_of(b.getShape(), False) - heading_of(a.getShape(), True) + 540) % 360 - 180
        if a.getName() != b.getName() and abs(turn) > 35:
            out.append((dist, "LEFT" if turn < 0 else "RIGHT", b.getName()))
    out.append((dist + route[-1].getLength(), "DESTINATION", ""))
    return out


def tls_link_states(net, route):
    """경로상 각 신호의 '내 차가 지나는 연결' (tlsID, linkIndex, 교차로 좌표)"""
    out = []
    for a, b in zip(route, route[1:]):
        for c in a.getOutgoing().get(b, []):
            if c.getTLSID():
                out.append((c.getTLSID(), c.getTLLinkIndex(), a.getToNode().getCoord()))
                break
    return out


def write_map(net, route, route_tls):
    """렌더러용 지도: 도로(차선 수), 건물(높이), 도로 이름, 역, 신호등, 경로"""
    lines = []
    for e in net.getEdges():
        if not e.allows("passenger"):
            continue
        pts = " ".join("%.1f,%.1f" % p for p in e.getShape())
        lines.append("R %d %s" % (e.getLaneNumber(), pts))
    root = ET.parse(OSM).getroot()
    nodes = {n.get("id"): (float(n.get("lon")), float(n.get("lat"))) for n in root.iter("node")}
    for w in root.iter("way"):
        tags = {t.get("k"): t.get("v") for t in w.iter("tag")}
        refs = [nd.get("ref") for nd in w.iter("nd") if nd.get("ref") in nodes]
        if len(refs) < 3:
            continue
        if "building" in tags:
            h = 9.0
            try:
                h = float(tags.get("height", "").replace("m", "")) if "height" in tags else float(tags.get("building:levels", 3)) * 3.2
            except ValueError:
                pass
            pts = " ".join("%.1f,%.1f" % net.convertLonLat2XY(*nodes[r]) for r in refs)
            lines.append("B %.1f %s" % (h, pts))
        elif tags.get("natural") == "water" or tags.get("waterway") == "riverbank":
            pts = " ".join("%.1f,%.1f" % net.convertLonLat2XY(*nodes[r]) for r in refs)
            lines.append("W %s" % pts)
    for n in root.iter("node"):
        tags = {t.get("k"): t.get("v") for t in n.iter("tag")}
        if tags.get("railway") == "station" or tags.get("public_transport") == "station":
            x, y = net.convertLonLat2XY(float(n.get("lon")), float(n.get("lat")))
            lines.append("S %.1f %.1f %s역" % (x, y, tags.get("name", "")))
    # 도로 이름: 같은 이름은 220m 마다 하나씩 (지도를 확대해서 봐도 보이도록)
    placed = []
    for e in sorted(net.getEdges(), key=lambda e: -e.getLength()):
        nm = e.getName()
        if not nm or e.getLength() < 50 or e.getFunction() == "internal" or e.getType().endswith("service"):
            continue
        (x1, y1), (x2, y2) = e.getShape()[0], e.getShape()[-1]
        mx, my = (x1 + x2) / 2, (y1 + y2) / 2
        if any(n == nm and math.hypot(mx - px, my - py) < 220 for n, px, py in placed):
            continue
        placed.append((nm, mx, my))
        lines.append("N %.1f %.1f %.1f %s" % (mx, my, math.degrees(math.atan2(y2 - y1, x2 - x1)), nm))
    for tid, idx, (x, y) in route_tls:
        lines.append("T %s %d %.1f %.1f" % (tid, idx, x, y))
    pts = " ".join("%.1f,%.1f" % p for e in route for p in e.getShape())
    lines.append("P %s" % pts)
    with open(MAP, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def intersection_names(net, route):
    """경로상 신호 교차로 이름: 만나는 도로 이름들 (예: 해운대로 · 센텀4로)"""
    names = {}
    for a in route:
        n = a.getToNode()
        if not n.getType().startswith("traffic_light"):
            continue
        cross = sorted({e.getName() for e in n.getIncoming() + n.getOutgoing() if e.getName() and e.getName() != a.getName()})
        names[n.getID()] = (a.getName() + (" · " + cross[0] if cross else " 횡단보도"))
    return names


def main():
    random.seed(7)
    build_net()
    build_background()
    net = sumolib.net.readNet(NET, withPrograms=True)
    start = edge_near(net, 35.1792, 129.1258, "해운대로", 135)
    goal = edge_near(net, 35.1690, 129.1372, "APEC로", None, 120)
    route, _ = net.getShortestPath(start, goal)
    route_ids = [e.getID() for e in route]
    route_tls = tls_link_states(net, route)
    write_map(net, route, route_tls)
    turns = maneuvers(route)
    junction_names = intersection_names(net, route)

    traci.start([os.path.join(BIN, "sumo"), "-n", NET, "-r", TRIPS, "--step-length", str(STEP),
                 "--no-warnings", "true", "--no-step-log", "true", "--time-to-teleport", "-1", "--seed", "7"])
    traci.vehicletype.copy("DEFAULT_VEHTYPE", "ego_t")
    traci.vehicletype.setMaxSpeed("ego_t", 50 / 3.6)
    traci.vehicletype.setDecel("ego_t", 1.6)            # 편안한 감속: 사람처럼 일찍, 부드럽게 멈춤
    traci.vehicletype.setEmergencyDecel("ego_t", 7.5)
    traci.vehicletype.setTau("ego_t", 1.2)
    traci.vehicletype.setImperfection("ego_t", 0.0)
    traci.route.add("ego_r", route_ids)
    depart = 120.0  # 배경 교통이 자리 잡은 뒤 출발
    while traci.simulation.getTime() < depart:
        traci.simulationStep()
    traci.vehicle.add("ego", "ego_r", typeID="ego_t", departSpeed="max", departLane="best")
    traci.vehicle.setColor("ego", (0, 158, 115))

    rows = []
    distracted_at = None      # 빨간불을 못 보고 계속 달리는 장면
    brake_at = None
    hold_until = None         # 초록불이 됐는데 늦게 출발하는 장면
    red_events_done = False
    hold_done = False
    stops_seen = 0
    prev_state = {}
    t0 = None
    while True:
        traci.simulationStep()
        now = traci.simulation.getTime()
        if "ego" not in traci.vehicle.getIDList():
            if t0 is not None:
                break
            continue
        if t0 is None:
            t0 = now
        t = now - t0
        speed = traci.vehicle.getSpeed("ego")
        nxt = traci.vehicle.getNextTLS("ego")
        # 카메라에는 앞의 신호들이 함께 보인다: 150m 안에서 초록이 아닌(더 제한적인) 첫 신호를 기준으로
        gov = next((n for n in nxt if n[2] <= 150 and n[3] not in ("G", "g")), nxt[0] if nxt else None)
        tls_id, tls_dist, tls_state = (gov[0], gov[2], gov[3]) if gov else ("", -1.0, "")

        # --- 장면 1: 두 번째로 만나는 빨간불을 처음엔 못 봄 (계속 달림) -> 긴급 경고 후 1초 뒤 제동 ---
        lead_now = traci.vehicle.getLeader("ego", 120)
        clear_ahead = not lead_now or not lead_now[0] or lead_now[1] > 90
        if not red_events_done and distracted_at is None and stops_seen >= 1 and tls_state in ("r", "y") \
                and 70 < tls_dist < 110 and speed > 10 and clear_ahead:
            distracted_at = t
            traci.vehicle.setSpeedMode("ego", 7)   # 앞차와의 안전거리는 지키되 신호는 무시 (운전자가 신호를 못 봄)
            traci.vehicle.setSpeed("ego", speed)
        if distracted_at is not None and brake_at is None:
            v = speed
            room = tls_dist - v * REACTION_S
            need = float("inf") if room <= 0.5 else v * v / (2 * room)
            if need >= URGENT_DECEL:
                brake_at = t + REACTION_S                 # 경고를 듣고 반응하기까지
        if brake_at is not None and t >= brake_at and not red_events_done:
            traci.vehicle.setSpeedMode("ego", 31)
            traci.vehicle.setSpeed("ego", -1)
            red_events_done = True

        # --- 장면 2: 세 번째 정차 신호에서 초록불이 됐는데 4초 늦게 출발 ---
        if speed < 0.3 and tls_dist >= 0 and tls_dist < 25 and tls_state in ("r",):
            if prev_state.get("stopped_at") != tls_id:
                stops_seen += 1
                prev_state["stopped_at"] = tls_id
        if not hold_done and stops_seen >= 3 and speed < 0.3 and tls_state in ("G", "g") and hold_until is None and red_events_done:
            hold_until = t + 4.0
            traci.vehicle.setSpeed("ego", 0)
        if hold_until is not None and t >= hold_until and not hold_done:
            traci.vehicle.setSpeed("ego", -1)
            hold_done = True

        # --- 평소 운전: 사람처럼 일찍·부드럽게(1.6 m/s²) 정지 (SUMO 기본 모델은 마지막 순간에 세게 제동함) ---
        distracted_now = distracted_at is not None and not red_events_done
        holding = hold_until is not None and not hold_done
        if not distracted_now and not holding:
            v = speed
            yellow_stoppable = tls_state in ("y", "Y") and v * v / (2 * 3.0) <= max(0.0, tls_dist - v * 1.0)
            if 0 <= tls_dist < 130 and (tls_state in ("r", "R") or yellow_stoppable):
                target = math.sqrt(max(0.0, 2 * HUMAN_DECEL * (tls_dist - 1.5)))
                traci.vehicle.setSpeed("ego", min(50 / 3.6, target))
            else:
                traci.vehicle.setSpeed("ego", -1)

        x, y = traci.vehicle.getPosition("ego")
        lon, lat = net.convertXY2LonLat(x, y)
        angle = traci.vehicle.getAngle("ego")
        road = net.getEdge(traci.vehicle.getRoadID("ego")).getName() if not traci.vehicle.getRoadID("ego").startswith(":") else ""
        travelled = traci.vehicle.getDistance("ego")
        nav = next(((d - travelled, m, nm) for d, m, nm in turns if d - travelled > 0), (0.0, "DESTINATION", ""))
        leader = traci.vehicle.getLeader("ego", 100)
        lead_gap, lead_brake = ("", 0)
        if leader and leader[0]:
            lead_gap = "%.1f" % max(0.0, leader[1])
            lead_brake = 1 if traci.vehicle.getAcceleration(leader[0]) < -0.5 or traci.vehicle.getSpeed(leader[0]) < 0.5 else 0
        states = "".join(traci.trafficlight.getRedYellowGreenState(tid)[idx] for tid, idx, _ in route_tls)
        others = []
        for vid in traci.vehicle.getIDList():
            if vid == "ego":
                continue
            ox, oy = traci.vehicle.getPosition(vid)
            if abs(ox - x) < 350 and abs(oy - y) < 350:
                others.append("%.0f:%.0f:%.0f" % (ox, oy, traci.vehicle.getAngle(vid)))
        jn = ""
        if tls_id:
            node_id = next((c[0].getEdge().getToNode().getID() for c in net.getTLS(tls_id).getConnections()), "")
            jn = junction_names.get(node_id, "")
        rows.append([
            "%.1f" % t, "%.2f" % (speed * 3.6), "%.1f" % tls_dist, tls_state, lead_gap, str(lead_brake),
            road, nav[1], "%.1f" % nav[0], nav[2], "%.1f" % x, "%.1f" % y, "%.1f" % angle, "%.6f" % lat, "%.6f" % lon,
            states, jn, " ".join(others[:60]),
        ])
        if t > 600:
            break
    traci.close()
    with open(TRACE, "w", encoding="utf-8") as f:
        f.write("t|speed_kmh|tls_dist|tls_state|lead_gap|lead_brake|road|nav_maneuver|nav_dist|nav_road|x|y|angle|lat|lon|route_tls|junction|others\n")
        for r in rows:
            f.write("|".join(r) + "\n")
    print("trace rows:", len(rows), "duration %.1fs" % float(rows[-1][0]), "route tls:", len(route_tls),
          "distracted@", distracted_at, "brake@", brake_at, "hold@", hold_until)


if __name__ == "__main__":
    main()
