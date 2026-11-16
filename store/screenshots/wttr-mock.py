"""A stand-in for wttr.in's ?format=j1 for the store screenshots (scripts/store-captures.sh): the same weather
for any city, dated today, so the today widget and the manga face look the same in every shot whatever wttr.in
is doing. Serves on 127.0.0.1:<port>, which the emulator reaches as 10.0.2.2.

    python3 -I store/screenshots/wttr-mock.py [port, default 8787]
"""

import datetime
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ICON = "https://cdn.worldweatheronline.com/images/wsymbols01_png_64/"
DESC = {"113": "Clear", "116": "Partly cloudy", "119": "Cloudy", "296": "Light rain"}


def f(c):
    return str(round(c * 9 / 5 + 32))


def hour(t, c, code, rain):
    night = t < 600 or t > 1700
    icon = ("wsymbol_0008_clear_sky_night" if night else "wsymbol_0001_sunny") if code == "113" else "wsymbol_0002_sunny_intervals"
    return {
        "time": str(t), "tempC": str(c), "tempF": f(c), "weatherCode": code, "chanceofrain": str(rain),
        "weatherDesc": [{"value": DESC[code]}], "weatherIconUrl": [{"value": ICON + icon + ".png"}],
    }


def day(date, lo, hi, codes, rains, moon, lit):
    temps = [lo, lo, lo + 2, hi - 1, hi, hi - 2, lo + 3, lo + 1]
    return {
        "date": date.isoformat(), "mintempC": str(lo), "maxtempC": str(hi), "mintempF": f(lo), "maxtempF": f(hi),
        "astronomy": [{"sunrise": "07:12 AM", "sunset": "04:31 PM", "moon_phase": moon, "moon_illumination": str(lit),
                       "moonrise": "03:40 PM", "moonset": "08:05 AM"}],
        "hourly": [hour(i * 300, temps[i], codes[i], rains[i]) for i in range(8)],
    }


def weather():
    today = datetime.date.today()
    night = not 7 <= datetime.datetime.now().hour < 17
    clear = "wsymbol_0008_clear_sky_night" if night else "wsymbol_0001_sunny"
    return {
        "current_condition": [{
            "temp_C": "11", "temp_F": f(11), "FeelsLikeC": "9", "FeelsLikeF": f(9), "weatherCode": "113",
            "weatherDesc": [{"value": "Clear"}], "weatherIconUrl": [{"value": ICON + clear + ".png"}],
            "uvIndex": "2", "humidity": "71", "precipMM": "0.0", "precipInches": "0.0", "pressure": "1021",
            "windspeedKmph": "11", "windspeedMiles": "7", "winddir16Point": "WSW", "visibility": "10", "cloudcover": "10",
        }],
        "nearest_area": [{"areaName": [{"value": "Berlin"}], "country": [{"value": "Germany"}],
                          "region": [{"value": "Berlin"}], "latitude": "52.520", "longitude": "13.405"}],
        "weather": [
            day(today, 6, 14, ["113", "113", "116", "116", "113", "113", "113", "113"], [0, 0, 5, 10, 0, 0, 0, 0], "Waxing Gibbous", 82),
            day(today + datetime.timedelta(1), 7, 13, ["116"] * 4 + ["119"] * 4, [5, 5, 10, 20, 30, 20, 10, 5], "Waxing Gibbous", 89),
            day(today + datetime.timedelta(2), 5, 11, ["119", "296", "296", "296", "119", "116", "116", "113"], [40, 70, 80, 75, 50, 30, 20, 10], "Full Moon", 97),
        ],
    }


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        body = json.dumps(weather()).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1]) if len(sys.argv) > 1 else 8787), Handler).serve_forever()
