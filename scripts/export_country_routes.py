"""Export selected GeoIP entries using the official protobuf wire schema."""
import ipaddress
import json
from pathlib import Path
from google.protobuf import descriptor_pb2, descriptor_pool, message_factory

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'android/app/src/main/assets'


def geoip_list_type():
    schema = descriptor_pb2.FileDescriptorProto(name='country_geoip.proto', package='spicygeo', syntax='proto3')
    def message(name, fields):
        msg = schema.message_type.add(name=name)
        for name, number, kind, repeated, target in fields:
            field = msg.field.add(name=name, number=number, type=kind,
                                  label=3 if repeated else 1)
            if target:
                field.type_name = '.spicygeo.' + target
    message('CIDR', [('ip', 1, 12, False, None), ('prefix', 2, 13, False, None)])
    message('GeoIP', [('country_code', 1, 9, False, None), ('cidr', 2, 11, True, 'CIDR'),
                      ('reverse_match', 3, 8, False, None)])
    message('GeoIPList', [('entry', 1, 11, True, 'GeoIP')])
    pool = descriptor_pool.DescriptorPool()
    pool.Add(schema)
    return message_factory.GetMessageClass(pool.FindMessageTypeByName('spicygeo.GeoIPList'))


def export():
    result = {}
    for country, filename in [('ir', 'geoip_c4u.dat'), ('ru', 'geoip.dat'), ('cn', 'geoip_cn.dat')]:
        data = geoip_list_type()()
        data.ParseFromString((ASSETS / filename).read_bytes())
        entries = [entry for entry in data.entry if entry.country_code.lower() == country]
        if len(entries) != 1 or entries[0].reverse_match:
            raise ValueError('Missing or invalid GeoIP entry: ' + country)
        networks = [ipaddress.ip_network((ipaddress.ip_address(cidr.ip), cidr.prefix), strict=False)
                    for cidr in entries[0].cidr]
        result[country] = [str(network) for version in (4, 6)
                           for network in ipaddress.collapse_addresses(n for n in networks if n.version == version)]
        if not result[country]:
            raise ValueError('Empty country routes: ' + country)
    target = ASSETS / 'country_ip_routes.json'
    target.write_text(json.dumps(result, separators=(',', ':')), encoding='utf-8')
    print('Exported country route counts:', {key: len(value) for key, value in result.items()})


if __name__ == '__main__':
    export()
