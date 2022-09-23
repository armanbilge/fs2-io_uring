#include <liburing.h>

struct io_uring_sqe *fs2_io_uring_get_sqe(struct io_uring *ring) {
  return io_uring_get_sqe(ring);
}

void fs2_io_uring_cq_advance(struct io_uring *ring, unsigned nr) {
  io_uring_cq_advance(ring, nr);
}

void fs2_io_uring_prep_nop(struct io_uring_sqe *sqe) { io_uring_prep_nop(sqe); }

void fs2_io_uring_prep_accept(struct io_uring_sqe *sqe, int fd,
                              struct sockaddr *addr, socklen_t *addrlen,
                              int flags) {
  io_uring_prep_accept(sqe, fd, addr, addrlen, flags);
}

void fs2_io_uring_prep_cancel64(struct io_uring_sqe *sqe, __u64 user_data,
                                int flags) {
  io_uring_prep_cancel64(sqe, user_data, flags);
}

void fs2_io_uring_prep_close(struct io_uring_sqe *sqe, int fd) {
  void io_uring_prep_close(sqe, fd);
}

void fs2_io_uring_prep_connect(struct io_uring_sqe *sqe, int fd,
                               const struct sockaddr *addr, socklen_t addrlen) {
  io_uring_prep_connect(sqe, fd, addr, addrlen);
}

void fs2_io_uring_prep_recv(struct io_uring_sqe *sqe, int sockfd, void *buf,
                            size_t len, int flags) {
  io_uring_prep_recv(sqe, sockfd, buf, len, flags);
}

void fs2_io_uring_prep_send(struct io_uring_sqe *sqe, int sockfd,
                            const void *buf, size_t len, int flags) {
  io_uring_prep_send(sqe, sockfd, buf, len, flags);
}

void fs2_io_uring_prep_shutdown(struct io_uring_sqe *sqe, int fd, int how) {
  io_uring_prep_shutdown(sqe, fd, how);
}

void fs2_io_uring_prep_socket(struct io_uring_sqe *sqe, int domain, int type,
                              int protocol, unsigned int flags) {
  io_uring_prep_socket(sqe, domain, type, protocol, flags);
}
