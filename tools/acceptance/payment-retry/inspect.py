import json
import sqlite3
import sys
from pathlib import Path


database, purchase_id = sys.argv[1:]
connection = sqlite3.connect(Path(database).as_uri() + "?mode=ro", uri=True)
connection.row_factory = sqlite3.Row

purchase = connection.execute(
    "SELECT id,nation,depot,resource,item_count,price,state,seller,settlement "
    "FROM purchase WHERE id=?",
    (purchase_id,),
).fetchone()
transfers = connection.execute(
    "SELECT id,purchase,player,resource,state,note,created FROM item_transfer "
    "WHERE purchase=? ORDER BY created,id",
    (purchase_id,),
).fetchall()
settlements = connection.execute(
    "SELECT id,order_id,nation,player,kind,amount,state,updated FROM settlement "
    "WHERE order_id=? ORDER BY updated,id",
    (purchase_id,),
).fetchall()
lots = connection.execute(
    "SELECT transfer,ordinal,item_id,original_count,remaining,warehouse "
    "FROM item_lot WHERE transfer IN (SELECT id FROM item_transfer WHERE purchase=?) "
    "ORDER BY transfer,ordinal",
    (purchase_id,),
).fetchall()
warehouse = connection.execute(
    "SELECT id,world,x,y,z FROM warehouse WHERE id=(SELECT depot FROM purchase WHERE id=?)",
    (purchase_id,),
).fetchone()

print(
    json.dumps(
        {
            "purchase": dict(purchase) if purchase else None,
            "transfers": [dict(row) for row in transfers],
            "settlements": [dict(row) for row in settlements],
            "lots": [dict(row) for row in lots],
            "warehouse": dict(warehouse) if warehouse else None,
        }
    )
)
connection.close()
