/* -*- Mode: C; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 8 -*- */
#if defined(__KERNEL__)
#include <linux/ip.h>
#else
#include <netinet/ip.h>
#endif
#include <scaffold/platform.h>
#include <scaffold/skbuff.h>
#include "scaffold_sock.h"

struct scaffold_table scaffold_table;

int __init scaffold_table_init(struct scaffold_table *table, const char *name)
{
	unsigned int i;

	table->hash = MALLOC(SCAFFOLD_HTABLE_SIZE_MIN *
			      2 * sizeof(struct scaffold_hslot), GFP_KERNEL);
	if (!table->hash) {
		/* panic(name); */
		return -1;
	}

	table->mask = SCAFFOLD_HTABLE_SIZE_MIN - 1;

	for (i = 0; i <= table->mask; i++) {
		INIT_HLIST_HEAD(&table->hash[i].head);
		table->hash[i].count = 0;
		spin_lock_init(&table->hash[i].lock);
	}

	return 0;
}

void __exit scaffold_table_fini(struct scaffold_table *table)
{
        unsigned int i;

        for (i = 0; i <= table->mask; i++) {
                spin_lock_bh(&table->hash[i].lock);
                        
                while (!hlist_empty(&table->hash[i].head)) {
                        struct scaffold_sock *ssk;

                        ssk = hlist_entry(table->hash[i].head.first, 
                                        struct scaffold_sock, node);
                        
                        hlist_del(&ssk->node);
                        table->hash[i].count--;
                        sock_put(&ssk->sk);
                }
                spin_unlock_bh(&table->hash[i].lock);           
	}

        FREE(table->hash);
}

int scaffold_table_insert(struct scaffold_table *table, struct sock *sk)
{
        struct scaffold_sock *ssk = scaffold_sk(sk);
        struct scaffold_hslot *slot;

        slot = scaffold_hashslot(table, sock_net(sk), &ssk->sockid);

        if (!slot)
                return -1;

        spin_lock_bh(&slot->lock);
        slot->count++;
        hlist_add_head(&ssk->node, &slot->head);
        sock_hold(sk);
        spin_unlock_bh(&slot->lock);     
        
        return 0;
}

static struct sock *scaffold_table_lookup(struct scaffold_table *table,
                                          struct net *net,
                                          struct sock_id *sockid)
{
        struct scaffold_hslot *slot;
        struct hlist_node *walk;
        struct sock *sk = NULL;
        struct scaffold_sock *ssk;

        slot = scaffold_hashslot(table, net, sockid);

        if (!slot)
                return NULL;

        spin_lock_bh(&slot->lock);
        
        hlist_for_each_entry(ssk, walk, &slot->head, node) {
                if (memcmp(sockid, &ssk->sockid, sizeof(struct sock_id)) == 0) {
                        sk = &ssk->sk;
                        sock_hold(sk);
                        break;
                }
        }

        spin_unlock_bh(&slot->lock);
        
        return sk;
}

struct sock *scaffold_table_lookup_sockid(struct sock_id *sockid)
{
        return scaffold_table_lookup(&scaffold_table, &init_net, sockid);
}

struct sock *scaffold_table_lookup_skb(struct sk_buff *skb)
{
 	struct sock *sk = NULL;
        /*
	const struct iphdr *iph = ip_hdr(skb);
        struct sock_id sockid;
        switch (iph->protocol) {
	case IPPROTO_TCP:
        {
                struct tcphdr *tcp = tcp_hdr(skb);
                memcpy(&sockid, &tcp->dest, sizeof(sockid));
                sk = scaffold_table_lookup(&scaffold_table, &init_net, &sockid);
                break;
        }
	case IPPROTO_UDP:
        {
                struct udphdr *udp = udp_hdr(skb);
                memcpy(&sockid, &udp->dest, sizeof(sockid));
                sk = scaffold_table_lookup(&scaffold_table, &init_net, &sockid);
                break;
        }
        default:
                break;
        }
        */

        return sk;
}

int __init scaffold_sock_init(void)
{
        int ret;

        ret = scaffold_table_init(&scaffold_table, "SCAFFOLD");

        if (ret == -1) {
                goto fail_table;
        }

fail_table:
        return ret;
}

void __exit scaffold_sock_fini(void)
{
        scaffold_table_fini(&scaffold_table);
}