package com.yuier.yuni.plugin.runtime.session;

/**
 * 群内用户归属键——保证“一个用户在一个群内只能处于一个通道”。
 *
 * @param groupId 群号
 * @param userId  用户 QQ 号
 */
record GroupUserKey(long groupId, long userId) {
}
