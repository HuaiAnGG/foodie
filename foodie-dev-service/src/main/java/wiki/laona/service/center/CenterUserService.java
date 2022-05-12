package wiki.laona.service.center;

import wiki.laona.pojo.Users;

/**
 * @author laona
 * @description 用户中心相关服务
 * @since 2022-05-12 16:58
 **/
public interface CenterUserService {

    /**
     * 根据用户id查询用户信息
     *
     * @param userId 用户id
     * @return 用户信息
     */
    public Users queryUserInfo(String userId);
}