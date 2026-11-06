"""Credential-free tests: the request shapes, the result parsing and the whole
flow against a loopback mock API. No network and no installed packages."""
import io
import unittest

import unique_exit as ue


class RequestTest(unittest.TestCase):
    def test_best_available_by_default(self):
        body = ue.create_body(1)
        self.assertIs(body["proxy_config"]["unique_exit"], True)
        self.assertEqual(body["proxy_config"]["initial_device_state"],
                         {"location": {"connect_location_id": {"best_available": True}}})
        self.assertNotIn("unique_exit_replace", body["proxy_config"])

    def test_country_and_replace_mode(self):
        body = ue.create_body(2, country="DE", replace="manual")
        self.assertEqual(body["proxy_config"]["initial_device_state"], {"country_code": "de"})
        self.assertEqual(body["proxy_config"]["unique_exit_replace"], "manual")

    def test_rejects_bad_inputs(self):
        with self.assertRaises(ValueError):
            ue.create_body(1, country="Germany")
        with self.assertRaises(ValueError):
            ue.create_body(1, replace="sometimes")

    def test_api_url_must_be_https(self):
        self.assertEqual(ue.endpoint("https://api.bringyour.com", ue.AUTH_CLIENT_PATH),
                         "https://api.bringyour.com/network/auth-client")
        self.assertEqual(ue.endpoint("http://127.0.0.1:8080", ue.AUTH_CLIENT_PATH),
                         "http://127.0.0.1:8080/network/auth-client")
        for bad in ("http://api.bringyour.com", "https://user:pw@api.bringyour.com", "https://api.bringyour.com/x"):
            with self.assertRaises(ValueError):
                ue.endpoint(bad, ue.AUTH_CLIENT_PATH)


class ResultTest(unittest.TestCase):
    EXIT_ID = "019a5d70-2c11-7b0e-8a51-0f6c3d2e9b47"
    RESULT = {
        "client_id": "019a5d6e-1d8e-7a3c-9c44-6b7f2a1e0c55",
        "proxy_config_result": {
            "proxy_host": "proxy.example", "auth_token": "tok", "socks_proxy_port": 1080,
            "https_proxy_url": "https://tok.proxy.example:443",
            "exit": {"exit_id": EXIT_ID, "country_code": "de", "region": "Berlin", "city": "Berlin",
                     "exit_since": "2026-11-05T19:00:00Z", "state": "assigned", "replace_mode": "auto"},
        },
    }

    def test_parses_the_exit_and_both_proxy_urls(self):
        proxy = ue.parse_created(self.RESULT)
        self.assertEqual(proxy["exit"]["exit_id"], self.EXIT_ID)
        self.assertEqual(proxy["https_proxy_url"], "https://tok.proxy.example:443")
        # the token is the SOCKS5 username, with a placeholder password
        self.assertEqual(proxy["socks_proxy_url"], "socks5h://tok:x@proxy.example:1080")
        line = ue.describe_exit(proxy["exit"])
        self.assertIn("de / Berlin / Berlin", line)
        self.assertIn("replace auto", line)

    def test_describes_a_waiting_exit_with_its_reason(self):
        line = ue.describe_exit({"state": "no_supply", "state_reason": "offline", "replace_mode": "manual"})
        self.assertIn("no_supply: offline", line)
        self.assertIn("no exit yet", line)

    def test_refusals_carry_the_server_message_and_flags(self):
        # (error, limit, upgrade, no_unique_supply, rate_limited)
        cases = [
            ({"client_limit_exceeded": False, "no_unique_supply": True,
              "message": "No unique exit IP is available in this location right now."}, False, False, True, False),
            ({"client_limit_exceeded": True, "message": "Too many new proxies this minute."}, True, False, False, False),
            ({"client_limit_exceeded": False, "upgrade_required": True,
              "message": "Unique exit IPs are a UR Pro feature."}, False, True, False, False),
            ({"rate_limited": True, "message": "This proxy rotated in the last minute."}, False, False, False, True),
        ]
        for error, limit, upgrade, no_supply, rate_limited in cases:
            with self.assertRaises(ue.Refused) as caught:
                ue.parse_created({"error": error})
            refused = caught.exception
            self.assertEqual(str(refused), error["message"])
            self.assertEqual((refused.limit, refused.upgrade, refused.no_unique_supply, refused.rate_limited),
                             (limit, upgrade, no_supply, rate_limited))

    def test_distinct_report_flags_shared_exits(self):
        distinct, duplicates = ue.distinct_report({"a": "192.0.2.1", "b": "192.0.2.2", "c": "192.0.2.1"})
        self.assertEqual(distinct, 2)
        self.assertEqual(duplicates, {"192.0.2.1": ["a", "c"]})


class FlowTest(unittest.TestCase):
    def setUp(self):
        self.server, self.base = ue.start_mock()
        ue.MockApi.calls.clear()
        ue.MockApi.rotate_refusal = None

    def tearDown(self):
        ue.MockApi.rotate_refusal = None
        self.server.shutdown()
        self.server.server_close()

    def test_a_refused_rotate_still_removes_the_proxies(self):
        for refusal, expected in (
                ({"rate_limited": True, "message": "This proxy rotated in the last minute."}, "rate limited"),
                ({"no_unique_supply": True, "message": "No unique exit IP is available."}, "keeps its exit")):
            ue.MockApi.calls.clear()
            ue.MockApi.rotate_refusal = refusal
            out = io.StringIO()
            ue.run(ue.Api(self.base, "root"), 2, None, None, True, ue.DEFAULT_ECHO_URL, False, out,
                   echo=ue.mock_echo, sleep=lambda seconds: None)
            self.assertIn(expected, out.getvalue())
            paths = [path for path, _, _ in ue.MockApi.calls]
            self.assertEqual(paths.count(ue.REMOVE_CLIENT_PATH), 2)

    def test_no_unique_supply_stops_creating(self):
        class NoSupplyApi(ue.Api):
            def create(self, body):
                raise ue.Refused("No unique exit IP is available in this location right now.",
                                 no_unique_supply=True)
        out = io.StringIO()
        created = ue.create_proxies(NoSupplyApi(self.base, "root"), 3, "is", None, out, sleep=lambda s: None)
        self.assertEqual(created, [])
        self.assertIn("no unique exit IP is left in this location", out.getvalue())

    def test_creates_rotates_and_removes(self):
        out = io.StringIO()
        api = ue.Api(self.base, "root-jwt-not-printed")
        created = ue.run(api, 3, "fr", None, True, ue.DEFAULT_ECHO_URL, False, out,
                         echo=ue.mock_echo, sleep=lambda seconds: None)
        paths = [path for path, _, _ in ue.MockApi.calls]
        self.assertEqual(paths.count(ue.AUTH_CLIENT_PATH), 3)
        self.assertEqual(paths.count(ue.ROTATE_EXIT_PATH), 1)
        self.assertEqual(paths.count(ue.REMOVE_CLIENT_PATH), 3)
        for path, authorization, body in ue.MockApi.calls:
            self.assertEqual(authorization, "Bearer root-jwt-not-printed")
            if path == ue.AUTH_CLIENT_PATH:
                self.assertEqual(body["proxy_config"]["initial_device_state"], {"country_code": "fr"})
        text = out.getvalue()
        self.assertIn("3 proxies, 3 distinct exit IPs", text)
        self.assertIn("rotated proxy 1", text)
        # no credential is ever printed
        self.assertNotIn("root-jwt-not-printed", text)
        for proxy in created:
            self.assertNotIn(proxy["auth_token"] + ".", text)
            self.assertNotIn(proxy["auth_token"] + ":", text)

    def test_keep_leaves_the_proxies(self):
        api = ue.Api(self.base, "root")
        ue.run(api, 2, None, None, False, ue.DEFAULT_ECHO_URL, True, io.StringIO(),
               echo=ue.mock_echo, sleep=lambda seconds: None)
        paths = [path for path, _, _ in ue.MockApi.calls]
        self.assertEqual(paths.count(ue.REMOVE_CLIENT_PATH), 0)

    def test_paces_creates_above_the_pro_rate(self):
        slept = []
        api = ue.Api(self.base, "root")
        ue.create_proxies(api, ue.PRO_CREATES_PER_MINUTE + 1, None, None, io.StringIO(), sleep=slept.append)
        # 21 creates, 20 waits of 60/20 = 3 s between them
        self.assertEqual(slept, [3.0] * ue.PRO_CREATES_PER_MINUTE)

    def test_self_test(self):
        out = io.StringIO()
        ue.self_test(out)
        self.assertIn("self-test ok", out.getvalue())


if __name__ == "__main__":
    unittest.main()
