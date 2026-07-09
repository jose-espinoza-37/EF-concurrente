

import sys
import threading
import time

from raft.raft_config import RaftConfig
from raft.raft_node import RaftNode
from raft.raft_server import RaftServer
from raft.state_machine import StateMachine


def _status_loop(node: RaftNode):
    while True:
        time.sleep(5)
        print(node.describe_status())


def main():
    if len(sys.argv) < 3:
        print("Uso: python raft_node_main.py <selfId> <rutaClusterProperties>")
        sys.exit(1)

    self_id = sys.argv[1]
    config_path = sys.argv[2]

    config = RaftConfig(config_path, self_id)
    state_machine = StateMachine()
    node = RaftNode(config, state_machine)
    server = RaftServer(config.self_port, node)

    server_thread = threading.Thread(target=server.serve_forever, name="raft-server-%s" % self_id, daemon=False)
    server_thread.start()

    node.start()

    status_thread = threading.Thread(target=_status_loop, args=(node,), daemon=True)
    status_thread.start()

    try:
        server_thread.join()
    except KeyboardInterrupt:
        server.stop()
        node.stop()


if __name__ == "__main__":
    main()
