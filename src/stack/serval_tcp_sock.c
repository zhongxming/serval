/* -*- Mode: C; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 8 -*- */
#include <serval/platform.h>
#include <serval/debug.h>
#include <serval/sock.h>
#include <serval_tcp.h>
#include <serval_tcp_sock.h>

void serval_tsk_init_xmit_timers(struct sock *sk,
			       void (*retransmit_handler)(unsigned long),
			       void (*delack_handler)(unsigned long),
			       void (*keepalive_handler)(unsigned long))
{
	struct serval_tcp_sock *tp = serval_tcp_sk(sk);

	setup_timer(&tp->retransmit_timer, retransmit_handler,
			(unsigned long)sk);
	setup_timer(&tp->delack_timer, delack_handler,
			(unsigned long)sk);
	setup_timer(&sk->sk_timer, keepalive_handler, (unsigned long)sk);
	tp->pending = tp->tp_ack.pending = 0;
}

void serval_tsk_clear_xmit_timers(struct sock *sk)
{
	struct serval_tcp_sock *tp = serval_tcp_sk(sk);

        LOG_DBG("clearing xmit timers!\n");
	tp->pending = tp->tp_ack.pending = tp->tp_ack.blocked = 0;

	sk_stop_timer(sk, &tp->retransmit_timer);
	sk_stop_timer(sk, &tp->delack_timer);
	sk_stop_timer(sk, &sk->sk_timer);
}

void serval_tsk_delete_keepalive_timer(struct sock *sk)
{
	sk_stop_timer(sk, &sk->sk_timer);
}

void serval_tsk_reset_keepalive_timer(struct sock *sk, unsigned long len)
{
        LOG_DBG("resetting timer\n");
	sk_reset_timer(sk, &sk->sk_timer, jiffies + len);
}
