#!/usr/bin/env python3
"""Publishes a SmartPhoneX phone's status to ROS 2. Read-only, and not yet tested with ROS 2.

    python3 ros2_bridge.py http://PHONE:8080 PHONE_TOKEN

Topics: /smartphonex/status (JSON text) and /smartphonex/attitude (roll, pitch and heading in degrees).
Needs a ROS 2 install providing rclpy. Commands aren't accepted here, on purpose: to add them, go
through /api/tools, so that they pass the phone's normal checks.
"""
import json
import sys
import urllib.request

import rclpy
from rclpy.node import Node
from std_msgs.msg import Float64MultiArray, String


def call(base, key, tool):
    req = urllib.request.Request(f"{base}/api/tools/{tool}", data=b"{}", method="POST",
                                 headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=2) as r:
        return json.loads(json.load(r)["content"][0]["text"])


class Bridge(Node):
    def __init__(self, base, key):
        super().__init__("smartphonex_bridge")
        self.base, self.key = base.rstrip("/"), key
        self.status = self.create_publisher(String, "smartphonex/status", 10)
        self.attitude = self.create_publisher(Float64MultiArray, "smartphonex/attitude", 10)
        self.create_timer(0.1, self.tick)

    def tick(self):
        try:
            s = call(self.base, self.key, "vehicle_get_status")
        except Exception as e:  # The link comes and goes: keep trying.
            self.get_logger().warning(str(e), throttle_duration_sec=5.0)
            return
        self.status.publish(String(data=json.dumps(s)))
        self.attitude.publish(Float64MultiArray(data=[s.get("rollDeg", 0.0), s.get("pitchDeg", 0.0), s.get("headingDeg", 0.0)]))


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    rclpy.init()
    rclpy.spin(Bridge(sys.argv[1], sys.argv[2]))


if __name__ == "__main__":
    main()
