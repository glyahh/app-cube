import importlib.util
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1] / 'checks'
sys.path.insert(0, str(ROOT))
spec = importlib.util.spec_from_file_location('framework_verifier', ROOT / 'verify-import-frameworks.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class FrameworkMappingTest(unittest.TestCase):
    def test_async_install_waits_for_authoritative_preparation(self):
        calls = []
        records = []
        def call(path, body=None):
            calls.append((path, body))
            if body is not None:
                return {'jobId': 'job', 'state': 'RUNNING'}
            return {'jobId': 'job', 'state': 'SUCCEEDED', 'preparation': {'state': 'SUCCEEDED'}}
        result = module.observe_install(call, 'app', {'approved': True}, records.append, sleep=lambda _: None)
        self.assertEqual(result['state'], 'SUCCEEDED')
        self.assertEqual(len(calls), 2)
        self.assertIn('requestId', calls[0][1])
        self.assertEqual(records[0]['state'], 'RUNNING')

    def test_lost_post_response_queries_same_request_without_resubmitting(self):
        calls = []
        def call(path, body=None):
            calls.append((path, body))
            if body is not None:
                raise TimeoutError()
            return {'state': 'REJECTED', 'preparation': {'state': 'REJECTED'}}
        result = module.observe_install(call, 'app', {'approved': False}, lambda _: None)
        self.assertEqual(result['state'], 'REJECTED')
        self.assertTrue(calls[1][0].endswith(calls[0][1]['requestId']))
        self.assertEqual(sum(body is not None for _, body in calls), 1)

    def test_observation_timeout_keeps_active_job_and_never_deletes(self):
        calls = []
        records = []
        def call(path, body=None):
            calls.append(path)
            return {'jobId': 'job', 'state': 'RUNNING'}
        with self.assertRaisesRegex(TimeoutError, 'INSTALL_OBSERVATION_TIMED_OUT'):
            module.observe_install(call, 'app', {}, records.append, observation_seconds=0)
        self.assertEqual(len(calls), 1)
        self.assertEqual(records[-1]['state'], 'RUNNING')

    def test_reverse_response_order_keeps_correct_frontend_and_backend(self):
        services = [{'name': 'Vue页面'}, {'name': 'Java接口'}]
        ids = {'Vue页面': 'front-id', 'Java接口': 'back-id'}
        rows = [{'serviceId': 'back-id', 'address': 'http://localhost:1/'},
                {'serviceId': 'front-id', 'address': 'http://localhost:2/'}]
        mapped = module.selected_views(services, ids, rows)
        self.assertEqual(mapped['Vue页面']['address'], 'http://localhost:2/')
        self.assertEqual(mapped['Java接口']['address'], 'http://localhost:1/')

    def test_duplicate_identity_is_not_counted_as_two_ready_services(self):
        with self.assertRaises(AssertionError):
            module.selected_views([{'name': 'a'}], {'a': 'x'}, [{'serviceId': 'x'}, {'serviceId': 'x'}])

    def test_unselected_service_is_not_accepted(self):
        with self.assertRaises(AssertionError):
            module.selected_views([{'name': 'a'}], {'a': 'x'}, [{'serviceId': 'other'}])


if __name__ == '__main__':
    unittest.main()
