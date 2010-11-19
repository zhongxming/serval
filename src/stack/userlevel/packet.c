/* -*- Mode: C; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 8 -*- */
#include <sys/socket.h>
#include <netpacket/packet.h>
#include <net/ethernet.h> /* the L2 protocols */
#include <netinet/ether.h>
#include <net/if_packet.h>
#include <net/if.h>
#include <scaffold/debug.h>
#include <string.h>
#include <errno.h>
#include <pthread.h>
#include <unistd.h>
#include <poll.h>
#include <scaffold/platform.h>
#include <scaffold/skbuff.h>
#include <input.h>

static int psock = -1;
static int pipefd[2] = { -1, -1 };
static int should_exit = 0;
static pthread_t pthr;

int packet_signal(void)
{
        char w = 'w';
        return write(pipefd[1], &w, 1);
}

void *packet_thread(void *arg)
{
        int ret = 0;
        
        LOG_DBG("Packet thread running\n");

        while (!should_exit) {
                struct pollfd fds[2];
                
                fds[0].fd = psock;
                fds[0].events = POLLIN | POLLHUP | POLLERR;
                fds[0].revents = 0;
                fds[1].fd = pipefd[0];
                fds[1].events = POLLIN | POLLERR;
                fds[1].revents = 0;

                ret = poll(fds, 2, -1);

                if (ret == -1) {
                        LOG_ERR("poll error: %s\n", strerror(errno));
                } else if (ret == 0) {
                        /* No timeout set, should not happen */
                } else {
                        if (fds[1].revents) {
                                should_exit = 1;
                                LOG_DBG("Packet thread should exit\n");
                        }
                        if (fds[0].revents) {
#define RCVLEN 2000 /* Should be more than enough for normal MTUs */
                                struct sk_buff *skb;
                                struct sockaddr_ll lladdr;
                                socklen_t addrlen = sizeof(lladdr);
                                unsigned char buf[RCVLEN];
                                char ifname[IFNAMSIZ];

                                ret = recvfrom(psock, buf, RCVLEN, 0,
                                               (struct sockaddr *)&lladdr, 
                                               &addrlen);

                                if (ret == -1) {
                                        LOG_ERR("recvfrom: %s\n", 
                                                strerror(errno));
                                        continue;
                                } else if (ret == 0) {
                                        /* Should not happen */
                                        continue;
                                }
                                
                                switch (lladdr.sll_pkttype) {
                                case PACKET_HOST:
                                        break;
                                case PACKET_BROADCAST:
                                case PACKET_MULTICAST:
                                case PACKET_OUTGOING:
                                case PACKET_OTHERHOST:
                                case PACKET_LOOPBACK:
                                default:
                                        continue;                
                                }
                                
                                skb = alloc_skb(ret);
                                
                                if (!skb)
                                        continue;
                                
                                memcpy(skb->data, buf, ret);

                                if_indextoname(lladdr.sll_ifindex, ifname);

                                ret = skb_alloc_and_set_netdevice(skb, 
                                                                  lladdr.sll_ifindex, 
                                                                  ifname);
                                
                                if (ret < 0) {
                                        LOG_ERR("Could not allocate net device\n");
                                        free_skb(skb);
                                        continue;
                                }
                                
                                skb->dev->ifindex = lladdr.sll_ifindex;
                                
                                skb_reset_mac_header(skb);
                                skb->pkt_type = lladdr.sll_pkttype;

                                /* Set head to network part of packet */
                                skb_pull(skb, ETH_HLEN);
                                
                                /* Set network header offset */
                                skb_reset_network_header(skb);

                                ret = scaffold_input(skb);

                                switch (ret) {
                                case INPUT_KEEP:
                                        break;
                                case INPUT_OK:
                                case INPUT_ERROR:
                                default:
                                        if (IS_INPUT_ERROR(ret)) {
                                                LOG_ERR("input error\n");
                                        }
                                        free_skb(skb);
                                        break;
                                }
                        }
                }
        }
        return NULL;
}

int packet_init(void)
{
	psock = socket(AF_PACKET, SOCK_RAW, htons(ETH_P_IP));

        if (psock == -1) {
                LOG_ERR("packet socket: %s\n", strerror(errno));
                return -1;
        }

        if (pipe(pipefd) == -1) {
                LOG_ERR("could not create pipe\n");
                close(psock);
                psock = -1;
                return -1;
        }

        if (pthread_create(&pthr, NULL, packet_thread, NULL) != 0) {
                LOG_ERR("could not create pipe\n");
                close(psock);
                psock = -1;
                close(pipefd[0]);
                pipefd[0] = -1;
                close(pipefd[1]);
                pipefd[1] = -1;
        }
        return 0;
}

void packet_fini(void)
{
        int ret;

        packet_signal();

        ret = pthread_join(pthr, NULL);

        if (ret == -1) {
                LOG_ERR("Could not join\n");
        } else {
                LOG_DBG("Packet thread joined\n");
        }

        if (psock != -1)
                close(psock);

        if (pipefd[0] != -1)
                close(pipefd[0]);

        if (pipefd[1] != -1)
                close(pipefd[1]);
}