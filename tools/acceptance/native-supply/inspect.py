"""Read-only rows for one isolated acceptance army; never writes the server databases."""
import json
import sqlite3
import sys
from pathlib import Path

stage = Path(sys.argv[1]).resolve()
army = sys.argv[2]
database = stage / 'plugins' / 'StarSupply' / 'supply.db'
with sqlite3.connect(database.as_uri() + '?mode=ro', uri=True) as con:
    con.row_factory = sqlite3.Row
    con.execute('PRAGMA query_only=ON')
    con.execute('BEGIN')
    output = {'schema': con.execute('PRAGMA user_version').fetchone()[0]}
    output['native_supply_credit'] = [dict(row) for row in con.execute(
        'SELECT id,army,nation,slot,interval_ms,points,state,attempts,last_error '
        'FROM native_supply_credit WHERE army=? ORDER BY slot,id LIMIT 64', (army,))]
    output['consumption'] = [dict(row) for row in con.execute(
        'SELECT slot,interval_ms,consumed_at,resource,demand,units FROM consumption WHERE army=? '
        'ORDER BY slot,interval_ms,resource LIMIT 64', (army,)]
    output['front'] = [dict(row) for row in con.execute(
        'SELECT id,nation,kind,world,x,y,z,army FROM warehouse WHERE army=? LIMIT 16', (army,))]
    output['lots'] = [dict(row) for row in con.execute(
        'SELECT transfer,ordinal,warehouse,resource,item_id,quality,quality_ratio,original_count,remaining,'
        'units_per_item,shipment FROM item_lot '
        'WHERE warehouse IN (SELECT id FROM warehouse WHERE army=?) '
        'ORDER BY transfer,ordinal LIMIT 128', (army,))]
    con.rollback()
print(json.dumps(output, ensure_ascii=False))
