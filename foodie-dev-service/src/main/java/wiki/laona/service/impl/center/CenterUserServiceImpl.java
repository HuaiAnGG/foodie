package wiki.laona.service.impl.center;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import wiki.laona.mapper.UsersMapper;
import wiki.laona.pojo.Users;
import wiki.laona.service.center.CenterUserService;

/**
 * @author laona
 * @description 用户中心服务实现类
 * @since 2022-05-12 17:00
 **/
@Service
public class CenterUserServiceImpl implements CenterUserService {

    @Autowired
    private UsersMapper usersMapper;

    @Transactional(propagation = Propagation.SUPPORTS)
    @Override
    public Users queryUserInfo(String userId) {
        Users user = usersMapper.selectByPrimaryKey(userId);
        user.setPassword(null);
        return user;
    }
}