# Copyright 2017-2021 EPAM Systems, Inc. (https://www.epam.com/)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from datetime import datetime
import os
import json
import glob
import re
import requests
from subprocess import check_output
import urllib3
from time import sleep

CP_CAP_CUSTOM_ENDPOINT_PREFIX = 'CP_CAP_CUSTOM_TOOL_ENDPOINT_'

try:
        from pykube.config import KubeConfig
        from pykube.http import HTTPClient
        from pykube.http import HTTPError
        from pykube.objects import Pod
        from pykube.objects import Service
        from pykube.objects import Event
except ImportError:
        raise RuntimeError('pykube is not installed. KubernetesJobTask requires pykube.')

SVC_PORT_TMPL = 'svc-port-'
SVC_PATH_TMPL = 'svc-path-'
SVC_URL_TMPL = '{{"url" : "{external_schema}://{external_ip}:{edge_port}/{edge_location}", "name": {service_name}, "isDefault": {is_default_endpoint}, "sameTab": {is_same_tab} }}'
EDGE_ROUTE_LOCATION_TMPL = '{pod_id}-{endpoint_port}-{endpoint_num}'
EDGE_ROUTE_TARGET_TMPL = '{pod_ip}:{endpoint_port}'
EDGE_ROUTE_TARGET_PATH_TMPL = '{pod_ip}:{endpoint_port}/{endpoint_path}'
EDGE_ROUTE_NO_PATH_CROP = 'CP_EDGE_NO_PATH_CROP'
EDGE_EXTERNAL_APP = 'CP_EDGE_EXTERNAL_APP'
RUN_ID = 'runid'
API_UPDATE_SVC = 'run/{run_id}/serviceUrl'
API_GET_RUNS_LIST_DETAILS = 'runs?runIds={run_ids}'
NUMBER_OF_RETRIES = 10
SECS_TO_WAIT_BEFORE_RETRY = 15

EDGE_SVC_ROLE_LABEL = 'cloud-pipeline/role'
EDGE_SVC_ROLE_LABEL_VALUE = 'EDGE'
EDGE_SVC_HOST_LABEL = 'cloud-pipeline/external-host'
EDGE_SVC_PORT_LABEL = 'cloud-pipeline/external-port'

nginx_custom_domain_config_ext = '.srv.conf'
nginx_custom_domain_loc_suffix = 'CP_EDGE_CUSTOM_DOMAIN'
nginx_custom_domain_loc_tmpl = 'include {}; # ' + nginx_custom_domain_loc_suffix
nginx_root_config_path = '/etc/nginx/nginx.conf'
nginx_sites_path = '/etc/nginx/sites-enabled'
nginx_domains_path = '/etc/nginx/sites-enabled/custom-domains'
external_apps_domains_path = '/etc/nginx/external-apps'
nginx_loc_module_template = '/etc/nginx/endpoints-config/route.template.loc.conf'
nginx_srv_module_template = '/etc/nginx/endpoints-config/route.template' + nginx_custom_domain_config_ext
nginx_sensitive_loc_module_template = '/etc/nginx/endpoints-config/sensitive.template.loc.conf'
nginx_sensitive_routes_config_path = '/etc/nginx/endpoints-config/sensitive.routes.json'
nginx_system_endpoints_config_path = '/etc/nginx/endpoints-config/system_endpoints.json'
edge_service_port = 31000
edge_service_external_ip = ''
pki_search_path = '/opt/edge/pki/'
pki_search_suffix_cert = '-public-cert.pem'
pki_search_suffix_key = '-private-key.pem'
pki_default_cert = '/opt/edge/pki/ssl-public-cert.pem'
pki_default_cert_key = '/opt/edge/pki/ssl-private-key.pem'

urllib3.disable_warnings()
api_url = os.environ.get('API')
api_token = os.environ.get('API_TOKEN')
if not api_url or not api_token:
        print('API url or API token are not set. Exiting')
        exit(1)
edge_service_external_schema=os.environ.get('EDGE_EXTERNAL_SCHEMA', 'https')


api_headers = {'Content-Type': 'application/json',
               'Authorization': 'Bearer {}'.format(api_token)}

class ServiceEndpoint:
        def __init__(self, num, port, path, additional):
                self.num = num
                self.port = port
                self.path = path
                self.additional = additional

def do_log(msg):
        print('[{}] {}'.format(datetime.now().strftime("%Y-%m-%d %H:%M:%S"), msg))

def call_api(method_url, data=None):
        result = None
        for n in range(NUMBER_OF_RETRIES):
                try:
                        do_log('Calling API {}'.format(method_url))
                        response = None
                        if data:
                                response = requests.post(method_url, verify=False, data=data, headers=api_headers)
                        else:
                                response = requests.get(method_url, verify=False, headers=api_headers)
                        response_data = json.loads(response.text)
                        if response_data['status'] == 'OK':
                                do_log('API call status OK')
                                result = response_data
                        else:
                                err_msg = 'No error message available'
                                if 'message' in response_data:
                                        err_msg = response_data['message']
                                do_log('Error ocurred while calling API ({})\n{}'.format(method_url, err_msg))
                                do_log('As the API technically succeeded, it will not be retried')
                        break
                except Exception as api_exception:
                        do_log('Error ocurred while calling API ({})\n{}'.format(method_url, str(api_exception)))

                if n < NUMBER_OF_RETRIES - 1:
                        do_log('Sleep for {} sec and perform API call again ({}/{})'.format(SECS_TO_WAIT_BEFORE_RETRY, n + 2, NUMBER_OF_RETRIES))
                        sleep(SECS_TO_WAIT_BEFORE_RETRY)
                else:
                        do_log('All attempts failed. API call failed')
        return result


def run_sids_to_str(run_sids, is_principal):
        if not run_sids or len(run_sids) == 0:
                return ""
        return ",".join([shared_sid["name"] for shared_sid in run_sids if shared_sid["isPrincipal"] == is_principal])

def parse_pretty_url(pretty):
        try:
                pretty_obj = json.loads(pretty)
                if not pretty_obj:
                        return None
        except:
                pretty_obj = { 'path': pretty }

        pretty_domain = None
        pretty_path = None
        if 'domain' in pretty_obj and pretty_obj['domain']:
                pretty_domain = pretty_obj['domain']
        if 'path' in pretty_obj:
                pretty_path = pretty_obj['path']
                if pretty_path.startswith('/'):
                        pretty_path = pretty_path[len('/'):]

        if not pretty_domain and not pretty_path:
                return None
        else:
                return { 'domain': pretty_domain, 'path': pretty_path }

def substr_indices(lines, substr):
        return [i for i, line in enumerate(lines) if substr in line]

def store_file_from_lines(lines, path):
        with open(path, 'w') as path_file:
                path_file.write('\n'.join(lines))

def get_domain_config_path(domain, is_external_app=False):
        domains_path =  external_apps_domains_path if is_external_app else nginx_domains_path
        return os.path.join(domains_path, domain + nginx_custom_domain_config_ext)

def add_custom_domain(domain, location_block, is_external_app=False):
        if not os.path.isdir(nginx_domains_path):
                os.mkdir(nginx_domains_path)
        domain_path = get_domain_config_path(domain, is_external_app=is_external_app)
        domain_cert = search_custom_domain_cert(domain)
        domain_path_contents = None
        if os.path.exists(domain_path):
                with open(domain_path, 'r') as domain_path_file:
                        domain_path_contents = domain_path_file.read()
        else:
                with open(nginx_srv_module_template, 'r') as nginx_srv_module_template_file:
                        domain_path_contents = nginx_srv_module_template_file.read()
                domain_path_contents = domain_path_contents \
                                        .replace('{edge_route_server_name}', domain) \
                                        .replace('{edge_route_server_ssl_certificate}', domain_cert[0]) \
                                        .replace('{edge_route_server_ssl_certificate_key}', domain_cert[1])

        location_block_include = nginx_custom_domain_loc_tmpl.format(location_block)
        domain_path_lines = domain_path_contents.splitlines()

        # Check if the location_block already added to the domain config
        existing_loc = substr_indices(domain_path_lines, location_block_include)
        if len(existing_loc) != 0:
                do_log('Location block {} already exists for domain {}'.format(location_block, domain))
                return

        # If it's a new location entry - add it to the domain config after the {edge_route_location_block} line
        insert_loc = substr_indices(domain_path_lines, '# {edge_route_location_block}')
        if len(insert_loc) == 0:
                do_log('Cannot find an insert location in the domain config {}'.format(domain_path))
                return
        domain_path_lines.insert(insert_loc[-1] + 1, location_block_include)

        # Save the domain config back to file
        store_file_from_lines(domain_path_lines, domain_path)


def remove_custom_domain(domain, location_block, is_external_app=False):
        location_block_include = nginx_custom_domain_loc_tmpl.format(location_block)
        domain_path = get_domain_config_path(domain, is_external_app=is_external_app)
        if not os.path.exists(domain_path):
                return False
        domain_path_lines = []
        with open(domain_path, 'r') as domain_path_file:
                domain_path_contents = domain_path_file.read()
                domain_path_lines = domain_path_contents.splitlines()

        existing_loc = substr_indices(domain_path_lines, location_block_include)
        if len(existing_loc) == 0:
                return False
        del domain_path_lines[existing_loc[-1]]

        if (not is_external_app and sum(nginx_custom_domain_loc_suffix in line for line in domain_path_lines) == 0):
                # If no more location block exist in the domain - delete the config file
                # Do not delete if this is an "external application", where the server block is managed externally
                do_log('No more location blocks are available for {}, deleting the config file: {}'.format(domain, domain_path))
                os.remove(domain_path)
        else:
                # Save the domain config back to file
                store_file_from_lines(domain_path_lines, domain_path)
        return True

def remove_custom_domain_all(location_block):
        for domains_root_path in [ nginx_domains_path, external_apps_domains_path ]:
                domain_path_list = [f for f in glob.glob(domains_root_path + '/*' + nginx_custom_domain_config_ext)]
                for domain_path in domain_path_list:
                        custom_domain = os.path.basename(domain_path).replace(nginx_custom_domain_config_ext, '')
                        is_external_app = domains_root_path == external_apps_domains_path
                        if remove_custom_domain(custom_domain, location_block, is_external_app=is_external_app):
                                do_log('Removed {} location block from {} domain config'.format(location_block, custom_domain))

def search_custom_domain_cert(domain):
        domain_cert_list = [f for f in glob.glob(pki_search_path + '/*' + pki_search_suffix_cert)]
        domain_cert_candidates = []
        for cert_path in domain_cert_list:
                cert_name = os.path.basename(cert_path).replace(pki_search_suffix_cert, '')
                if domain.endswith(cert_name):
                        domain_cert_candidates.append(cert_name)

        cert_path = None
        key_path = None
        if len(domain_cert_candidates) > 0:
                domain_cert_candidates.sort(key=len, reverse=True)
                cert_name = domain_cert_candidates[0]
                cert_path = os.path.join(pki_search_path, cert_name + pki_search_suffix_cert)
                key_path = os.path.join(pki_search_path, cert_name + pki_search_suffix_key)
                if not os.path.isfile(key_path):
                        do_log('Certificate for {} is found at {}, but a key does not exist at {}'.format(domain, cert_path, key_path))
                        key_path = None
        if not cert_path or not key_path:
                cert_path = pki_default_cert
                key_path = pki_default_cert_key

        do_log('Certificate:Key for {} will be used: {}:{}'.format(domain, cert_path, key_path))
        return (cert_path, key_path)

def read_system_endpoints():
        system_endpoints = {}
        with open(nginx_system_endpoints_config_path, 'r') as system_endpoints_file:
                system_endpoints_list = json.load(system_endpoints_file)
                for endpoint in system_endpoints_list:
                        system_endpoints[endpoint['name']] = {
                                "value": "true",
                                "endpoint": str(os.environ.get(endpoint['endpoint_env'],
                                                               endpoint['endpoint_default'])),
                                "endpoint_num":  str(os.environ.get(endpoint['endpoint_num_env'],
                                                                    endpoint['endpoint_num_default'])),
                                "friendly_name": endpoint['friendly_name'],
                                "endpoint_additional": endpoint['endpoint_additional'] if 'endpoint_additional' in endpoint else '',
                                "endpoint_same_tab": endpoint['endpoint_same_tab'] if 'endpoint_same_tab' in endpoint else None,
                                "ssl_backend": endpoint['ssl_backend'] if 'ssl_backend' in endpoint else None
                        }
        return system_endpoints

SYSTEM_ENDPOINTS = read_system_endpoints()
SYSTEM_ENDPOINTS_NAMES = [endpoint['friendly_name'] for endpoint in SYSTEM_ENDPOINTS.values()]

def is_system_endpoint_name(endpoint):
        if endpoint and "name" in endpoint and endpoint["name"]:
                return endpoint["name"] in SYSTEM_ENDPOINTS_NAMES
        else:
                return False

# Function to construct endpoint was configured with Run Parameters.
# Group of Run Parameters started with CP_CAP_CUSTOM_TOOL_ENDPOINT_<num> considered as configuration of additional endpoint
# that should be available for this run. Full list of supported params are:
#
# CP_CAP_CUSTOM_TOOL_ENDPOINT_<num>_PORT
# CP_CAP_CUSTOM_TOOL_ENDPOINT_<num>_NAME
# CP_CAP_CUSTOM_TOOL_ENDPOINT_<num>_ADDITIONAL
# CP_CAP_CUSTOM_TOOL_ENDPOINT_<num>_NUM
# CP_CAP_CUSTOM_TOOL_ENDPOINT_<num>_SSL_BACKEND
# CP_CAP_CUSTOM_TOOL_ENDPOINT_<num>_SAME_TAB
#
# Method will group such parametes by <num> and construct from such group an endpoint.
def construct_additional_endpoints_from_run_parameters(run_details):

        def extract_endpoint_num_from_run_parameter(run_parameter):
                match = re.search('{}(\d+).*'.format(CP_CAP_CUSTOM_ENDPOINT_PREFIX), run_parameter["name"])
                if match:
                        return match.group(1)
                return None


        custom_endpoint_run_parameters = [rp for rp in run_details["pipelineRunParameters"]
                                          if rp["name"].startswith(CP_CAP_CUSTOM_ENDPOINT_PREFIX)]

        do_log('Found {} run parameters related to custom endpoints.'.format(len(custom_endpoint_run_parameters)))

        custom_endpoints_nums = set([CP_CAP_CUSTOM_ENDPOINT_PREFIX + extract_endpoint_num_from_run_parameter(rp)
                                     for rp in custom_endpoint_run_parameters])
        do_log('Run parameter groups with ids: {} related to custom endpoints were found.'.format(", ".join(str(num) for num in custom_endpoints_nums)))

        custom_endpoint_param_groups = {
                id : {
                        rp["name"] : rp["value"]
                        for rp in custom_endpoint_run_parameters if rp["name"].startswith(id)
                } for id in custom_endpoints_nums
        }

        return [
                {
                        "name" : e_id,
                        "endpoint": e.get(e_id + "_PORT"),
                        "friendly_name": e.get(e_id + "_NAME", "pipeline-" + str(run_details['id']) + "-" + e.get(e_id + "_PORT")),
                        "endpoint_additional": e.get(e_id + "_ADDITIONAL", ""),
                        "ssl_backend": e.get(e_id + "_SSL_BACKEND", "false"),
                        "endpoint_same_tab": e.get(e_id + "_SAME_TAB", "false")
                } for e_id, e in custom_endpoint_param_groups.items()
        ]

def append_additional_endpoints(tool_endpoints, run_details):
        if not tool_endpoints:
                tool_endpoints = []
        system_endpoints_params = SYSTEM_ENDPOINTS.keys()
        overridden_endpoints_count = 0
        if run_details and "pipelineRunParameters" in run_details:
                # Get a list of endpoints from SYSTEM_ENDPOINTS which match the run's parameters (param name and a value)
                additional_endpoints_to_configure = [SYSTEM_ENDPOINTS[x["name"]] for x in run_details["pipelineRunParameters"]
                                                     if x["name"] in system_endpoints_params
                                                        and x["value"] == SYSTEM_ENDPOINTS[x["name"]]["value"]
                                                        and "endpoint" in SYSTEM_ENDPOINTS[x["name"]]
                                                        and SYSTEM_ENDPOINTS[x["name"]]["endpoint"]]
                additional_endpoint_ports_to_configure = set([e["endpoint"] for e in additional_endpoints_to_configure])

                # Filter out any endpoint if it matches with system ones
                for custom_endpoint in construct_additional_endpoints_from_run_parameters(run_details):
                        if custom_endpoint["endpoint"] in additional_endpoint_ports_to_configure:
                                do_log('Endpoint {} with port: {} conflict with already configured ones, it will be filtered out.'
                                       .format(custom_endpoint["name"], custom_endpoint["endpoint"]))
                                continue
                        # Append additional custom endpoint that are configured with run parameters
                        additional_endpoints_to_configure.append(custom_endpoint)
                        additional_endpoint_ports_to_configure.add(custom_endpoint["endpoint"])

                # If only a single endpoint is defined for the tool - we shall make sure it is set to default. Otherwise "system endpoint" may become a default one
                # If more then one endpoint is defined - we shall not make the changes, as it is up to the owner of the tool
                if len(additional_endpoints_to_configure) > 0 and len(tool_endpoints) == 1:
                        current_tool_endpoint = json.loads(tool_endpoints[0])
                        current_tool_endpoint["isDefault"] = "true"
                        tool_endpoints[0] = json.dumps(current_tool_endpoint)

                # Append additional endpoints to the existing list
                for additional_endpoint in additional_endpoints_to_configure:
                        tool_endpoint = { "nginx": { "port": additional_endpoint["endpoint"], "additional": additional_endpoint["endpoint_additional"] }}
                        system_endpoint_port = additional_endpoint["endpoint"]
                        system_endpoint_ssl_backend = additional_endpoint["ssl_backend"]
                        system_endpoint_same_tab = additional_endpoint["endpoint_same_tab"]
                        system_endpoint_name = None
                        if "friendly_name" in additional_endpoint:
                                tool_endpoint["name"] = additional_endpoint["friendly_name"]
                                system_endpoint_name = additional_endpoint["friendly_name"]
                        if "endpoint_num" in additional_endpoint and additional_endpoint["endpoint_num"]:
                                tool_endpoint["endpoint_num"] = additional_endpoint["endpoint_num"]
                        non_matching_with_system_tool_endpoints, \
                                is_default_endpoint, \
                                is_ssl_backend, \
                                is_same_tab = \
                                remove_from_tool_endpoints_if_fully_matches(system_endpoint_name,
                                                                            system_endpoint_port, tool_endpoints)
                        removed_endpoints_count = len(tool_endpoints) - len(non_matching_with_system_tool_endpoints)
                        tool_endpoint["isDefault"] = str(is_default_endpoint).lower()
                        tool_endpoint["sslBackend"] = system_endpoint_ssl_backend if system_endpoint_ssl_backend else is_ssl_backend
                        tool_endpoint["sameTab"] = system_endpoint_same_tab if system_endpoint_same_tab else is_same_tab
                        if removed_endpoints_count != 0:
                                tool_endpoints = non_matching_with_system_tool_endpoints
                                overridden_endpoints_count += removed_endpoints_count
                        tool_endpoints.append(json.dumps(tool_endpoint))
        return tool_endpoints, overridden_endpoints_count

def remove_from_tool_endpoints_if_fully_matches(endpoint_name, endpoint_port, tool_endpoints):
        non_matching_tool_endpoints = []
        is_default_endpoint = False
        is_ssl_backend = False
        is_same_tab = False
        for endpoint in tool_endpoints:
                tool_endpoint_obj = json.loads(endpoint)
                if tool_endpoint_obj \
                        and endpoint_name \
                        and 'name' in tool_endpoint_obj \
                        and tool_endpoint_obj['name'] \
                        and tool_endpoint_obj['name'].lower() == endpoint_name.lower() \
                        and 'nginx' in tool_endpoint_obj \
                        and tool_endpoint_obj['nginx'] \
                        and 'port' in tool_endpoint_obj['nginx'] \
                        and tool_endpoint_obj['nginx']['port'] == endpoint_port:
                        if 'isDefault' in tool_endpoint_obj and tool_endpoint_obj['isDefault']:
                                is_default_endpoint = is_default_endpoint | tool_endpoint_obj['isDefault']
                        if 'sslBackend' in tool_endpoint_obj and tool_endpoint_obj['sslBackend']:
                                is_ssl_backend = is_ssl_backend | tool_endpoint_obj['sslBackend']
                        if 'sameTab' in tool_endpoint_obj and tool_endpoint_obj['sameTab']:
                                is_same_tab = is_same_tab | tool_endpoint_obj['sameTab']
                else:
                        non_matching_tool_endpoints.append(endpoint)
        return non_matching_tool_endpoints, is_default_endpoint, is_ssl_backend, is_same_tab

def get_active_runs(pods):
        pod_run_ids = [x['metadata']['labels']['runid'] for x in pods]
        get_runs_list_details_method = os.path.join(api_url,
                                                    API_GET_RUNS_LIST_DETAILS.format(run_ids=','.join(pod_run_ids)))
        response_data = call_api(get_runs_list_details_method)
        if not response_data or 'payload' not in response_data:
                do_log('Cannot get list of active runs from the API for the following IDs: {}'.format(pod_run_ids))
                return []

        return response_data["payload"]


def get_service_list(active_runs_list, pod_id, pod_run_id, pod_ip):
        service_list = {}
        run_cache = [cached_run for cached_run in active_runs_list if str(cached_run['pipelineRun']['id']) == str(pod_run_id)]
        run_cache = next(iter(run_cache), None)
        if not run_cache:
                do_log('Cannot find the RunID {} in the list of cached runs, skipping'.format(pod_run_id))
                return {}

        run_info = run_cache['pipelineRun']
        if run_info:
                if not run_info["status"] or run_info["status"] != 'RUNNING':
                        do_log('Status for pipeline with id: {}, is not RUNNING. Service urls will not been proxied'.format(pod_run_id))
                        return {}

                pod_owner = run_info["owner"]
                docker_image = run_info["dockerImage"]
                runs_sids = None
                if "runSids" in run_info:
                        runs_sids = run_info["runSids"]
                pretty_url = None
                if "prettyUrl" in run_info:
                        pretty_url = parse_pretty_url(run_info["prettyUrl"])
                sensitive = run_info.get("sensitive") or False


                do_log('User {} is determined as an owner of PodID ({}) - RunID ({})'.format(pod_owner, pod_id, pod_run_id))

                shared_users_sids = run_sids_to_str(runs_sids, True)
                if shared_users_sids and len(shared_users_sids) > 0:
                        do_log('Users {} are determined as shared sids of PodID ({}) - RunID ({})'.format(shared_users_sids, pod_id, pod_run_id))

                shared_groups_sids = run_sids_to_str(runs_sids, False)
                if shared_groups_sids and len(shared_groups_sids) > 0:
                        do_log('Groups {} are determined as shared sids of PodID ({}) - RunID ({})'.format(shared_groups_sids, pod_id, pod_run_id))

                registry, separator, image = docker_image.partition("/")

                if "tool" in run_cache and "endpoints" in run_cache["tool"]:
                        endpoints_data = run_cache["tool"]["endpoints"]
                else:
                        endpoints_data = []
                tool_endpoints_count = len(endpoints_data)
                do_log('{} endpoints are set for the tool {} via settings'.format(tool_endpoints_count, docker_image))
                endpoints_data, overridden_endpoints_count = append_additional_endpoints(endpoints_data, run_info)
                additional_system_endpoints_count = len(endpoints_data) - tool_endpoints_count
                do_log('{} additional system endpoints are set for the tool {} via run parameters'
                      .format(additional_system_endpoints_count, docker_image))
                if overridden_endpoints_count != 0:
                        do_log('{} endpoints are overridden by a system ones for the tool {} via run parameters'
                              .format(overridden_endpoints_count, docker_image))
                if endpoints_data:
                        endpoints_count = len(endpoints_data)
                        for i in range(endpoints_count):
                                endpoint = json.loads(endpoints_data[i])
                                if endpoint["nginx"]:
                                        port = endpoint["nginx"]["port"]
                                        path = endpoint["nginx"].get("path", "")
                                        service_name = '"' + endpoint["name"] + '"' if "name" in endpoint.keys() else "null"
                                        is_default_endpoint = '"' + str(endpoint["isDefault"]).lower() + '"' if "isDefault" in endpoint.keys() else '"false"'
                                        is_ssl_backend = str(endpoint["sslBackend"]).lower() == 'true' if "sslBackend" in endpoint.keys() else False
                                        is_same_tab = '"' + str(endpoint["sameTab"]).lower() + '"' if "sameTab" in endpoint.keys() else '"false"'
                                        additional = endpoint["nginx"].get("additional", "")
                                        has_explicit_endpoint_num = "endpoint_num" in endpoint.keys()
                                        custom_endpoint_num = int(endpoint["endpoint_num"]) if has_explicit_endpoint_num else i
                                        if not pretty_url or (has_explicit_endpoint_num and not is_system_endpoint_name(endpoint)):
                                                edge_location = EDGE_ROUTE_LOCATION_TMPL.format(pod_id=pod_id, endpoint_port=port, endpoint_num=custom_endpoint_num)
                                        else:
                                                pretty_url_path = pretty_url["path"]
                                                if endpoints_count == 1:
                                                        edge_location = pretty_url_path
                                                else:
                                                        pretty_url_suffix = endpoint["name"] if "name" in endpoint.keys() else str(custom_endpoint_num)
                                                        if pretty_url_path:
                                                                edge_location = '{}-{}'.format(pretty_url_path, pretty_url_suffix)
                                                        else:
                                                                edge_location = pretty_url_suffix

                                        if pretty_url and pretty_url['domain']:
                                                edge_location_id = '{}-{}.inc'.format(pretty_url['domain'], edge_location.replace('/', '-') if edge_location else None)
                                        else:
                                                edge_location_id = '{}.loc'.format(edge_location)

                                        edge_target = \
                                                EDGE_ROUTE_TARGET_PATH_TMPL.format(pod_ip=pod_ip, endpoint_port=port, endpoint_path=path) \
                                                        if path \
                                                        else EDGE_ROUTE_TARGET_TMPL.format(pod_ip=pod_ip, endpoint_port=port)

                                        # If CP_EDGE_NO_PATH_CROP is present (any place) in the "additional" section of the route config
                                        # then trailing "/" is not added to the proxy pass target. This will allow to forward original requests trailing path
                                        if EDGE_ROUTE_NO_PATH_CROP in additional:
                                                additional = additional.replace(EDGE_ROUTE_NO_PATH_CROP, "")
                                        else:
                                                edge_target = edge_target + "/"

                                        is_external_app = False
                                        if EDGE_EXTERNAL_APP in additional:
                                                additional = additional.replace(EDGE_EXTERNAL_APP, "")
                                                is_external_app = True

                                        service_list[edge_location_id] = {"pod_id": pod_id,
                                                                        "pod_ip": pod_ip,
                                                                        "pod_owner": pod_owner,
                                                                        "shared_users_sids": shared_users_sids,
                                                                        "shared_groups_sids": shared_groups_sids,
                                                                        "service_name": service_name,
                                                                        "is_default_endpoint": is_default_endpoint,
                                                                        "is_ssl_backend": is_ssl_backend,
                                                                        "is_same_tab": is_same_tab,
                                                                        "edge_num": i,
                                                                        "edge_location": edge_location,
                                                                        "custom_domain": pretty_url['domain'] if pretty_url and 'domain' in pretty_url and pretty_url['domain'] else None,
                                                                        "edge_target": edge_target,
                                                                        "run_id": pod_run_id,
                                                                        "additional" : additional,
                                                                        "sensitive": sensitive,
                                                                        "external_app": is_external_app}
                else:
                        do_log('No endpoints required for the tool {}'.format(docker_image))
        else:
                do_log('Unable to get details of a RunID {} from API due to errors'.format(pod_run_id))
        return service_list

do_log('============ Started iteration ============')

kube_api = HTTPClient(KubeConfig.from_service_account())
kube_api.session.verify = False

edge_kube_service = Service.objects(kube_api).filter(selector={EDGE_SVC_ROLE_LABEL: EDGE_SVC_ROLE_LABEL_VALUE})
if len(edge_kube_service.response['items']) == 0:
        do_log('EDGE service is not found by label: cloud-pipeline/role=EDGE')
        exit(1)
else:
        edge_kube_service_object = edge_kube_service.response['items'][0]
        edge_kube_service_object_metadata = edge_kube_service_object['metadata']

        if 'labels' in edge_kube_service_object_metadata and EDGE_SVC_HOST_LABEL in edge_kube_service_object_metadata['labels']:
                edge_service_external_ip = edge_kube_service_object_metadata['labels'][EDGE_SVC_HOST_LABEL]

        if 'labels' in edge_kube_service_object_metadata and EDGE_SVC_PORT_LABEL in edge_kube_service_object_metadata['labels']:
                edge_service_port = edge_kube_service_object_metadata['labels'][EDGE_SVC_PORT_LABEL]

        if not edge_service_external_ip:
                edge_service_external_ip = edge_kube_service_object['spec']['externalIPs'][0]
        if not edge_service_port:
                edge_service_port = edge_kube_service_object['ports'][0]['nodePort']
        do_log('EDGE service port: ' + str(edge_service_port))
        do_log('EDGE service ip: ' + edge_service_external_ip)

# From each pod with a container, which has endpoints ("job-type=Service" or container's environment
# has a parameter from SYSTEM_ENDPOINTS) we shall take:
# -- PodIP
# -- PodID
# -- N entries by a template
# --- svc-port-N
# --- svc-path-N

def load_pods_for_runs_with_endpoints():
        pods_with_endpoints = []
        all_pipeline_pods = Pod.objects(kube_api).filter(selector={'type': 'pipeline'})\
                                                 .filter(field_selector={"status.phase": "Running"})
        for pod in all_pipeline_pods.response['items']:
                labels = pod['metadata']['labels']
                if 'job-type' in labels and labels['job-type'] == 'Service':
                        pods_with_endpoints.append(pod)
                        continue
                if 'spec' in pod \
                        and pod['spec'] \
                        and 'containers' in pod['spec'] \
                        and pod['spec']['containers'] \
                        and len(pod['spec']['containers']) > 0 \
                        and 'env' in pod['spec']['containers'][0] \
                        and pod['spec']['containers'][0]['env']:
                        pipeline_env_parameters = pod['spec']['containers'][0]['env']
                        matched_sys_endpoints = filter(lambda env_var: env_var['name'] in SYSTEM_ENDPOINTS.keys()
                                                                       and env_var['value'] == 'true',
                                                       pipeline_env_parameters)
                        if len(matched_sys_endpoints) > 0:
                                pods_with_endpoints.append(pod)
        return pods_with_endpoints

pods_with_endpoints = load_pods_for_runs_with_endpoints()
runs_with_endpoints = get_active_runs(pods_with_endpoints)

services_list = {}
for pod_spec in pods_with_endpoints:
        pod_id = pod_spec['metadata']['name']
        pod_ip = pod_spec['status']['podIP']
        pod_run_id = pod_spec['metadata']['labels']['runid']

        if not pod_run_id:
                do_log('RunID not found for pod: ' + pod_id + ', skipping')
                continue

        services_list.update(get_service_list(runs_with_endpoints, pod_id, pod_run_id, pod_ip))

do_log('Found ' + str(len(services_list)) + ' running PODs for interactive runs')

routes_kube = set([x for x in services_list])

# Find out existing routes from /etc/nginx/sites-enabled
nginx_modules_list = {}
for x in os.listdir(nginx_sites_path):
        if '.conf' in x and os.path.isfile(os.path.join(nginx_sites_path, x)):
                nginx_modules_list[x.replace('.conf', '')] = x
routes_current = set([x for x in nginx_modules_list])

# For each of the routes that exist in both Pods and NGINX we shall check, whether routes shall be updated
# If they do not match - nginx config will be deleted, thus it will be further recreated during "add" step
# For now only users/groups sharing is checked
routes_to_update = routes_current.intersection(routes_kube)
do_log('Found ' + str(len(routes_to_update)) + ' routes with existing configs, they will be checked for updates')

routes_were_updated = False
for update_route in routes_to_update:
        path_to_update_route = os.path.join(nginx_sites_path, nginx_modules_list[update_route])

        do_log('Checking nginx config for updates: {}'.format(path_to_update_route))
        with open(path_to_update_route) as update_route_file:
                update_route_file_contents = update_route_file.read()

        shared_users_sids_to_check = ""
        shared_groups_sids_to_check = ""
        for update_route_search_results in re.finditer(r"shared_with_users\s{1,}\"(.+?)\";|shared_with_groups\s{1,}\"(.+?)\";", update_route_file_contents):
                g1 = update_route_search_results.group(1)
                g2 = update_route_search_results.group(2)
                shared_users_sids_to_check = g1 if g1 and len(g1) > 0 else shared_users_sids_to_check
                shared_groups_sids_to_check = g2 if g2 and len(g2) > 0 else shared_groups_sids_to_check

        service_spec = services_list[update_route]
        shared_users_sids_to_update = service_spec["shared_users_sids"]
        shared_groups_sids_to_update = service_spec["shared_groups_sids"]

        do_log('- Shared users found: "{}", while expected: "{}"'.format(shared_users_sids_to_check, shared_users_sids_to_update))
        do_log('- Shared groups found: "{}", while expected: "{}"'.format(shared_groups_sids_to_check, shared_groups_sids_to_update))

        # If nginx config and settings from API do not match - delete nginx config
        if shared_users_sids_to_check != shared_users_sids_to_update or shared_groups_sids_to_check != shared_groups_sids_to_update:
                do_log('nginx config will be deleted {}'.format(path_to_update_route))
                os.remove(path_to_update_route)
                routes_current.remove(update_route)
                routes_were_updated = True

# Perform merge of the existing routes and pods
do_log('Found out expired and new routes ...')
routes_to_delete = routes_current - routes_kube
do_log('Found ' + str(len(routes_to_delete)) + ' expired routes, this routes will be deleted')
routes_to_add = routes_kube - routes_current
do_log('Found ' + str(len(routes_to_add)) + ' pods without routes, routes for this pods will be added')

# For each of the routes that are not present in the list of pods - delete files from /etc/nginx/sites-enabled
for obsolete_route in routes_to_delete:
        path_to_route = os.path.join(nginx_sites_path, nginx_modules_list[obsolete_route])
        do_log('Deleting obsolete route: ' + path_to_route)
        os.remove(path_to_route)
        remove_custom_domain_all(path_to_route)



# For each of the entries in the template of the new Pods we shall build nginx route in /etc/nginx/sites-enabled
# -- File name of the route: {PodID}-{svc-port-N}-{N}
# -- location /{PodID}-{svc-port-N}-{N}/ {
# --    proxy_pass http://{PodIP}:{svc-port-N}/;
# -- }

nginx_loc_module_template_contents = ''
with open(nginx_loc_module_template, 'r') as nginx_loc_module_template_file:
    nginx_loc_module_template_contents = nginx_loc_module_template_file.read()

nginx_sensitive_loc_module_template_contents = ''
with open(nginx_sensitive_loc_module_template, 'r') as nginx_sensitive_loc_module_template_file:
    nginx_sensitive_loc_module_template_contents = nginx_sensitive_loc_module_template_file.read()

sensitive_routes = []
with open(nginx_sensitive_routes_config_path, 'r') as sensitive_routes_file:
    sensitive_routes = json.load(sensitive_routes_file)

service_url_dict = {}
for added_route in routes_to_add:
        service_spec = services_list[added_route]

        has_custom_domain = service_spec["custom_domain"] is not None
        service_hostname = service_spec["custom_domain"] if has_custom_domain else edge_service_external_ip
        service_location = '/{}/'.format(service_spec["edge_location"]) if service_spec["edge_location"] else "/"
        # Replace the duplicated forward slashes with a single instance to workaround possible issue when the location is set to "/path//"
        service_location = re.sub('/+', '/', service_location)

        nginx_route_definition = nginx_loc_module_template_contents\
                .replace('{edge_route_location}', service_location)\
                .replace('{edge_route_target}', service_spec["edge_target"])\
                .replace('{edge_route_owner}', service_spec["pod_owner"]) \
                .replace('{run_id}', service_spec["run_id"]) \
                .replace('{edge_route_shared_users}', service_spec["shared_users_sids"]) \
                .replace('{edge_route_shared_groups}', service_spec["shared_groups_sids"]) \
                .replace('{edge_route_schema}', 'https' if service_spec["is_ssl_backend"] else 'http') \
                .replace('{additional}', service_spec["additional"])

        nginx_sensitive_route_definitions = []
        if service_spec["sensitive"]:
                for sensitive_route in sensitive_routes:
                        # proxy_pass cannot have trailing slash for regexp locations
                        edge_target = service_spec["edge_target"]
                        if edge_target.endswith("/"):
                                edge_target = edge_target[:-1]
                        nginx_sensitive_route_definition = nginx_sensitive_loc_module_template_contents \
                                .replace('{edge_route_location}', service_location + sensitive_route['route']) \
                                .replace('{edge_route_sensitive_methods}', '|'.join(sensitive_route['methods'])) \
                                .replace('{edge_route_target}', edge_target) \
                                .replace('{edge_route_owner}', service_spec["pod_owner"]) \
                                .replace('{run_id}', service_spec["run_id"]) \
                                .replace('{edge_route_shared_users}', service_spec["shared_users_sids"]) \
                                .replace('{edge_route_shared_groups}', service_spec["shared_groups_sids"]) \
                                .replace('{edge_route_schema}', 'https' if service_spec["is_ssl_backend"] else 'http') \
                                .replace('{additional}', service_spec["additional"])
                        nginx_sensitive_route_definitions.append(nginx_sensitive_route_definition)

        path_to_route = os.path.join(nginx_sites_path, added_route + '.conf')
        if service_spec["sensitive"]:
                do_log('Adding new sensitive route: ' + path_to_route)
        else:
                do_log('Adding new route: ' + path_to_route)
        with open(path_to_route, "w") as added_route_file:
                added_route_file.write(nginx_route_definition)
                if nginx_sensitive_route_definitions:
                        for nginx_sensitive_route_definition in nginx_sensitive_route_definitions:
                                added_route_file.write(nginx_sensitive_route_definition)

        if has_custom_domain:
                do_log('Adding {} route to the server block {}'.format(path_to_route, service_hostname))
                add_custom_domain(service_hostname, path_to_route, is_external_app=service_spec['external_app'])

        service_url = SVC_URL_TMPL.format(external_ip=service_hostname,
                                          edge_location=service_spec["edge_location"] if service_spec["edge_location"] else "",
                                          edge_port=str(edge_service_port),
                                          service_name=service_spec["service_name"],
                                          is_default_endpoint=service_spec["is_default_endpoint"],
                                          is_same_tab=service_spec["is_same_tab"],
                                          external_schema=edge_service_external_schema)
        run_id = service_spec["run_id"]
        if run_id in service_url_dict:
                service_url = service_url_dict[run_id] + ',' + service_url
        service_url_dict[run_id] = service_url

# Once all entries are added to the template - run "nginx -s reload"
# TODO: Add error handling, if non-zero is returned - restore previous state
if len(routes_to_add) > 0 or len(routes_to_delete) or routes_were_updated:
        do_log('Reloading nginx config')
        check_output('nginx -s reload', shell=True)


# For all added entries - call API and set Service URL property for the run:
# -- Get ServiceExternalIP from the EDGE-labeled service description
# -- http://{ServiceExternalIP}/{PodID}-{svc-port-N}-{N}

for run_id in service_url_dict:
        # make array of json objects
        service_urls_json = '[' + service_url_dict[run_id] + ']'
        update_svc_method = os.path.join(api_url, API_UPDATE_SVC.format(run_id=run_id))
        do_log('Assigning service url ({}) to RunID: {}'.format(service_urls_json, run_id))

        data = json.dumps({'serviceUrl': service_urls_json})
        response_data = call_api(update_svc_method, data=data)
        if response_data:
                do_log('Service url ({}) assigned to RunID: {}'.format(service_urls_json, run_id))
        else:
                do_log('Service url was not assigned due to API errors')

do_log('============ Done iteration ============')
print('')
