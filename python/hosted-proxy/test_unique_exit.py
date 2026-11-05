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
    RESULT = {
        "client_id": "019a5d6e-1d8e-7a3c-9c44-6b7f2a1e0c55",
        "proxy_config_result": {
            "proxy_host": "proxy.example", "auth_token": "tok", "socks_proxy_port": 1080,
            "https_proxy_url": "https://tok.proxy.example:443",
            "exit": {"exit_id": "ex_1", "country_code": "de", "region": "Berlin", "city": "Berlin",
                     "exit_since": "2026-11-05T19:00:00Z", "state": "assigned"},
        },
    }

    def test_parses_the_exit_and_both_proxy_urls(self):
        proxy = ue.parse_created(self.RESULT)
        self.assertEqual(proxy["exit"]["exit_id"], "ex_1")
        self.assertEqual(proxy["https_proxy_url"], "https://tok.proxy.example:443")
        # the token is the SOCKS5 username, with a placeholder password
        self.assertEqual(proxy["socks_proxy_url"], "socks5h://tok:x@proxy.example:1080")
        self.assertIn("de / Berlin / Berlin", ue.describe_exit(proxy["exit"]))

    def test_refusals_carry_the_server_message(self):
        cases = [
            ({"error": {"client_limit_exceeded": False, "message": "no_unique_supply"}}, False, False),
            ({"error": {"client_limit_exceeded": True, "message": "Too many new proxies this minute."}}, True, False),
            ({"error": {"client_limit_exceeded": True, "upgrade_required": True, "message": "Pro only"}}, True, True),
        ]
        for obj, limit, upgrade in cases:
            with self.assertRaises(ue.Refused) as caught:
                ue.parse_created(obj)
            self.assertEqual(str(caught.exception), obj["error"]["message"])
            self.assertEqual(caught.exception.limit, limit)
            self.assertEqual(caught.exception.upgrade, upgrade)

    def test_distinct_report_flags_shared_exits(self):
        distinct, duplicates = ue.distinct_report({"a": "192.0.2.1", "b": "192.0.2.2", "c": "192.0.2.1"})
        self.assertEqual(distinct, 2)
        self.assertEqual(duplicates, {"192.0.2.1": ["a", "c"]})


class FlowTest(unittest.TestCase):
    def setUp(self):
        self.server, self.base = ue.start_mock()
        ue.MockApi.calls.clear()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

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
